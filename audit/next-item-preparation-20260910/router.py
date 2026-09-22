from edit import read, write, replace
P='composeApp/src/androidMain/kotlin/com/yfuse/core2/android/AndroidAdaptiveCore2YPlayer.kt'
t=read(P)
t=t.replace('import java.util.concurrent.atomic.AtomicLong','import java.util.concurrent.atomic.AtomicLong\nimport java.util.concurrent.atomic.AtomicReference')
t=t.replace('    override fun selectItem(index: Int) {','''    private val nextPreparationBoundary = AtomicReference<NextItemPreparationBoundary?>(null)

    override fun setNextItemPreparation(itemId: String, transitionPositionMs: Long?, enabled: Boolean) {
        if (released) return
        val next = NextItemPreparationBoundary(itemId, transitionPositionMs?.takeIf { it > 0L }, enabled)
        if (nextPreparationBoundary.getAndSet(next) != next) commands.trySend(Command.PreparationBoundaryChanged)
    }

    override fun selectItem(index: Int) {''')
t=t.replace('        var preloadedNextRoute: PreloadedNextRoute? = null','''        var preloadedNextRoute: PreloadedNextRoute? = null
        var nextPreloadRetryAfterMs = 0L
        var handoffStartedNs: Long? = null
        var handoffVideoLogged = false
        var handoffAudioLogged = false
        fun discardNextPreparation() {
            nextItemPreloadJob?.cancel()
            nextItemPreloadJob = null
            preloadedNextRoute?.sources?.close()
            preloadedNextRoute = null
        }''')
start=t.index('        fun scheduleNextItemPreload(fromIndex: Int) {')
end=t.index('\n        fun createInternalEnhancedRoute(',start)
t=t[:start]+'''        fun scheduleNextItemPreload(fromIndex: Int) {
            if (!request.autoNext || fromIndex != currentIndex) return
            val preloadChild = child ?: return
            val currentItem = queueItems.getOrNull(fromIndex) ?: return
            val nextIndex = fromIndex + 1
            val item = queueItems.getOrNull(nextIndex) ?: return
            val hint = nextPreparationBoundary.get()?.takeIf { it.itemId == currentItem.id }
            if (hint?.enabled == false || item.disc != null || item.drmConfiguration != null) return
            val nowMs = System.nanoTime() / 1_000_000L
            val forcePowerSaver = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            val preferTunnel = item.allExternalSubtitles.isEmpty() && audioDelayMs == 0L &&
                kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            if (nextItemPreloadJob?.isActive == true || nowMs < nextPreloadRetryAfterMs ||
                preloadedNextRoute?.matches(nextIndex, item, preferTunnel, allowAudioPassthrough, forcePowerSaver) == true) return
            preloadedNextRoute?.sources?.close()
            preloadedNextRoute = null
            nextPreloadRetryAfterMs = nowMs + 10_000L
            nextItemPreloadJob = scope.launch(Dispatchers.IO) {
                val sources = AndroidNextItemSources()
                var transferred = false
                val preloadEvaluator = AndroidCore2RouteEvaluator(context)
                val preloadPassthrough = allowAudioPassthrough
                fun snapshot(): YPlayerState? = activeChild?.takeIf { it === preloadChild }?.state?.value
                fun boundary(): Long? = nextPreparationBoundary.get()?.takeIf { it.itemId == currentItem.id }?.positionMs
                fun allowed(): Boolean = !released && activeChild === preloadChild &&
                    nextPreparationBoundary.get()?.takeIf { it.itemId == currentItem.id }?.enabled != false &&
                    currentThermalStatus() < SEVERE_THERMAL_STATUS && nextItemNetworkAllowed(context)
                fun healthy(): Boolean = allowed() && snapshot()?.let(::nextItemPlaybackHealthy) == true
                try {
                    if (!awaitCore2NextItemPreloadWindow(::snapshot)) return@launch
                    if (!awaitNextItemBoundary(90_000L, ::boundary, ::snapshot, ::allowed)) return@launch
                    speculativeNextItemWork(::healthy) { budget -> warmNextItemBytes(context, item, budget) }
                    // Sources have a 30s lease. Open them only close to credits/natural end.
                    if (!awaitNextItemBoundary(20_000L, ::boundary, ::snapshot, ::allowed)) return@launch
                    val decision = speculativeNextItemWork({
                        healthy() && (snapshot()?.let { nextItemRemainingMs(it, boundary()) } ?: Long.MAX_VALUE) <= 25_000L
                    }) { budget ->
                        preloadEvaluator.evaluate(item, preferTunnel, preloadPassthrough, forcePowerSaver,
                            prepareSourceForPlayback = true, budget = budget)
                    } ?: return@launch
                    currentCoroutineContext().ensureActive()
                    if (!healthy()) return@launch
                    preloadEvaluator.takePreparedExtractor(item)?.let { sources.extractor.offer(item, it) }
                    preloadEvaluator.takePreparedEnhancedDemux(item)?.let { sources.enhanced.offer(item, it) }
                    val route = PreloadedNextRoute(nextIndex, item, preferTunnel, preloadPassthrough,
                        forcePowerSaver, decision, sources)
                    transferred = commands.trySend(Command.NextItemPreloaded(preloadChild, route)).isSuccess
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.warning(category = "player.core2", event = "next_item_preload_failed",
                        message = "Optional next-item preparation stopped; normal open remains available",
                        throwable = error, attributes = mapOf("itemIndex" to nextIndex.toString()))
                } finally {
                    preloadEvaluator.closePreparedExtractor()
                    preloadEvaluator.closePreparedEnhancedDemux()
                    if (!transferred) sources.close()
                }
            }
        }
''' + t[end:]
t=t.replace('''            if (warmedRoute != null) {
                preloadedNextRoute = null''','''            if (warmedRoute != null) {
                preloadedNextRoute = null
                val adopted = routeEvaluator.adoptPreparedSources(item, warmedRoute.sources)
                AppLog.info(category = "player.core2", event = "next_item_source_adopted",
                    message = "Next-item prepared source ownership resolved",
                    attributes = mapOf("adopted" to adopted.toString(), "itemIndex" to currentIndex.toString()))''')
# The old boolean could permanently suppress retries after cancellation, expiry or pressure.
t=t.replace('            var nextItemPreloadRequested = false\n','').replace('                            nextItemPreloadRequested = false\n','')
t=t.replace('                            !nextItemPreloadRequested &&\n','').replace('                            nextItemPreloadRequested = true\n','')
t=t.replace('''                        Command.Pause -> {
                            requestedPlay = false''','''                        Command.Pause -> {
                            discardNextPreparation()
                            requestedPlay = false''')
t=t.replace('''                            if (positionMs >= 0L) {
                                val seekFeedbackGeneration''','''                            if (positionMs >= 0L) {
                                discardNextPreparation()
                                nextPreloadRetryAfterMs = 0L
                                val seekFeedbackGeneration''')
t=t.replace('''                            if (preloadedNextRoute?.index != selectedIndex) preloadedNextRoute = null''','''                            if (preloadedNextRoute?.index != selectedIndex) {
                                preloadedNextRoute?.sources?.close()
                                preloadedNextRoute = null
                            }
                            handoffStartedNs = System.nanoTime()
                            handoffVideoLogged = false
                            handoffAudioLogged = false''')
t=t.replace('''                        Command.QueueUpdated -> {
                            nextItemPreloadJob?.cancel()
                            nextItemPreloadJob = null
                            preloadedNextRoute = null''','''                        Command.QueueUpdated -> {
                            discardNextPreparation()
                            nextPreloadRetryAfterMs = 0L''')
start=t.index('                        is Command.NextItemPreloaded -> {')
end=t.index('                        Command.AudioRouteChanged -> {',start)
t=t[:start]+'''                        Command.PreparationBoundaryChanged -> {
                            discardNextPreparation()
                            nextPreloadRetryAfterMs = 0L
                            scheduleNextItemPreload(currentIndex)
                        }
                        is Command.NextItemPreloaded -> {
                            val item = queueItems.getOrNull(command.route.index)
                            if (child !== command.fromChild || item == null ||
                                command.route.index != currentIndex + 1 ||
                                !command.route.sourceItem.matchesPreparedSource(item)) {
                                command.route.sources.close()
                                continue
                            }
                            nextItemPreloadJob = null
                            preloadedNextRoute?.sources?.close()
                            preloadedNextRoute = command.route
                            AppLog.info(category = "player.core2", event = "next_item_preloaded",
                                message = "YCore next-item route and expiring media sources prepared",
                                attributes = mapOf("itemIndex" to command.route.index.toString()))
                        }
''' +t[end:]
t=t.replace('''                        Command.ThermalPressure -> {''','''                        Command.ThermalPressure -> {
                            discardNextPreparation()''')
# Flush old cues and buffered ranges immediately at a committed item boundary.
t=t.replace('''                    currentIndex = currentIndex,
                    error = null,
                    errorCategory = null,
                    diagnostics =''','''                    currentIndex = currentIndex,
                    bufferedPositionMs = positionMs.coerceAtLeast(0L),
                    subtitleCues = emptyList(),
                    secondarySubtitleCues = emptyList(),
                    error = null,
                    errorCategory = null,
                    diagnostics =''')
# Readiness timings must use the new child's output evidence, not "Ready" or metadata.
needle='''                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            !learningRecorded'''
t=t.replace(needle,'''                        handoffStartedNs?.let { started ->
                            val video = childState.diagnostics.videoOutputVerified
                            val audio = childState.diagnostics.audioOutputVerified
                            if ((!handoffVideoLogged && video) || (!handoffAudioLogged && audio)) {
                                AppLog.info(category = "player.core2", event = "next_item_output_ready",
                                    message = "Output confirmed after item selection",
                                    attributes = mapOf("elapsedMs" to ((System.nanoTime() - started) / 1_000_000L).toString(),
                                        "video" to video.toString(), "audio" to audio.toString(),
                                        "itemIndex" to currentIndex.toString()))
                                handoffVideoLogged = handoffVideoLogged || video
                                handoffAudioLogged = handoffAudioLogged || audio
                            }
                        }
''' +needle)
t=t.replace('''            nextItemPreloadJob?.cancel()
            try {
                stopChild''','''            discardNextPreparation()
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                if (pending is Command.NextItemPreloaded) pending.route.sources.close()
            }
            try {
                stopChild''')
t=t.replace('''        data object QueueUpdated : Command''','''        data object QueueUpdated : Command

        data object PreparationBoundaryChanged : Command''')
t=t.replace('''        val itemId: String,
        val itemUri: String,
        val preferTunnel: Boolean,''','''        val sourceItem: YMediaItem,
        val preferTunnel: Boolean,''')
t=t.replace('''        val decision: YCore2RouteDecision,
    ) {''','''        val decision: YCore2RouteDecision,
        val sources: AndroidNextItemSources,
        val preparedAtNs: Long = System.nanoTime(),
    ) {''')
t=t.replace('''                itemId == item.id &&
                itemUri == item.uri &&''','''                System.nanoTime() - preparedAtNs < 30_000_000_000L &&
                sourceItem.matchesPreparedSource(item) &&
                sourceItem.sourceHints == item.sourceHints &&
                sourceItem.allExternalSubtitles == item.allExternalSubtitles &&''')
write(P,t)
