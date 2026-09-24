package com.yfuse.core2.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.yfuse.core.logging.AppLog
import com.yfuse.core.logging.diagnosticRootCause
import com.yfuse.core.logging.diagnosticTypeName
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core2.api.YInitialTrackSelection
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YOutputEvidenceResetReason
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerFactory
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.api.appendingDistinct
import com.yfuse.core2.api.invalidateOutputEvidence
import com.yfuse.core2.api.isPrematurePlaybackEnd
import com.yfuse.core2.api.preferenceIn
import com.yfuse.core2.api.trackSelectionSkipReason
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.learning.YLearnedRouteAdvice
import com.yfuse.core2.learning.YPlaybackLearningEngine
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.learning.YPlaybackObservation
import com.yfuse.core2.legacy.AndroidMpvCore2FallbackFactory
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.quirk.YCore2FailureLedger
import com.yfuse.core2.recovery.YPlaybackFailureReporter
import com.yfuse.core2.recovery.YPlaybackRecoveryAction
import com.yfuse.core2.recovery.YPlaybackRecoveryContext
import com.yfuse.core2.recovery.YPlaybackRecoveryPolicy
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.render.YNativeGpuRuntimeProbe
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-item Core2 router. It never assumes one queue shares one codec/HDR/audio route.
 *
 * Each selected item is probed independently and receives NativeTunnel, NativeDirect,
 * NativeEnhanced, GpuEnhanced, or SoftwareFallback. GPU/software tiers use an injected
 * verified compatibility executor. If no executable route can be
 * proven, state becomes Failed/Unknown so the product-level Legacy fallback can take over without
 * poisoning any decoder-specific failure memory.
 */
internal class AndroidAdaptiveCore2YPlayer(
    private val context: Context,
    private val request: YPlayerOpenRequest,
    private val routeEvaluator: AndroidCore2RouteEvaluator = AndroidCore2RouteEvaluator(context),
    private val fallbackRouteFactory: AndroidCore2FallbackRouteFactory? = null,
    private val discRouteFactory: AndroidCore2DiscRouteFactory? = null,
    private val allowAudioPassthrough: Boolean = true,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val nativeGpuRuntimeProbe: YNativeGpuRuntimeProbe = AndroidYCoreGpuRuntime.probe(context),
    private val preferSoftwareDecode: Boolean = false,
    private val preferredRemoteBufferTargetUs: Long? = null,
    private val failureLedger: YCore2FailureLedger =
        YCore2FailureLedger(
            store = AndroidYCore2FailureStore(context),
            nowEpochMs = System::currentTimeMillis,
        ),
    private val learningEngine: YPlaybackLearningEngine =
        YPlaybackLearningEngine(
            store = AndroidYPlaybackLearningStore(context),
            nowEpochMs = System::currentTimeMillis,
        ),
    private val adaptiveFeedbackSink: AndroidYCoreHttpProxy? = null,
    private val verifiedRouteMemory: AndroidYCoreVerifiedRouteMemory = AndroidYCoreVerifiedRouteMemory(context),
    private val onRelease: () -> Unit = {},
) : YPlayer,
    AndroidSerializedPlayerRelease {
    private val nativeOnly = fallbackRouteFactory == null
    private val queueLock = Any()

    /**
     * Owned here rather than inside [runLoop] so a caller can wait for the last child decoder
     * after the worker itself has finished: [releaseAndJoin] must not return while a MediaCodec
     * the router handed to its child is still tearing down.
     */
    private val releaseBarrier = AndroidPlayerReleaseBarrier()

    @Volatile
    private var queueItems = request.items

    private val mutableState =
        MutableStateFlow(
            YPlayerState(
                phase = YPlaybackPhase.Idle,
                playbackRequested = request.autoPlay,
                positionMs = request.startPositionMs,
                currentIndex = request.startIndex,
                itemCount = queueItems.size,
                diagnostics =
                    YPlayerDiagnostics(
                        route = YPlaybackRoute.Legacy,
                        reason = "YCore 2.0 route not evaluated yet",
                    ),
            ),
        )
    override val state: StateFlow<YPlayerState> = mutableState.asStateFlow()
    val sourceFacts = routeEvaluator.sourceFacts.asStateFlow()
    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    /**
     * Every router coroutine - the command loop, the child-state collector and the monitors - runs
     * one at a time on this view of Default. The loop and the collector both mutate the recovery
     * counters and the next-item preparation; on plain Default a collector write could land inside
     * the loop's iteration of the same map, and the ConcurrentModificationException had no handler.
     *
     * Confinement rather than posting child states through [commands]: the posting design would
     * queue position and failure handling behind whatever the loop is executing and would mean
     * rewriting the collector's per-child recovery edges as loop state. Here both keep their code
     * and simply interleave at suspension points. Nothing on this view may block for long, so the
     * one step that does - route evaluation, probing and child construction - runs on IO (see
     * createChildOffRouter), and the next-item preload launches on IO itself.
     */
    private val scope = CoroutineScope(SupervisorJob() + androidCore2RouterDispatcher())
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker = scope.launch { runLoop() }
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val batteryManager = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val thermalMonitor =
        scope.launch {
            var severe = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            while (isActive) {
                delay(THERMAL_POLL_INTERVAL_MS)
                val nextSevere = currentThermalStatus() >= SEVERE_THERMAL_STATUS
                if (nextSevere && !severe) commands.trySend(Command.ThermalPressure)
                severe = nextSevere
            }
        }
    private val audioCallbackHandler = Handler(Looper.getMainLooper())
    private val audioRouteChangeQueued = AtomicBoolean(false)
    private val deferredAudioRouteChange = AtomicBoolean(false)
    private val audioOutputFingerprintLock = Any()

    /**
     * Seeded here, before the spatializer monitor and device callback below are registered.
     *
     * Both of those can report immediately - registerAudioDeviceCallback always does - and a
     * change measured against an empty baseline is indistinguishable from a real one.
     */
    @Volatile
    private var observedAudioOutputFingerprint = currentAudioOutputFingerprint()

    private val spatialAudioStateMonitor =
        createAndroidSpatialAudioStateMonitor(context, ::queueAudioRouteChange)
    private val seekCommandQueued = AtomicBoolean(false)
    private val pendingSeekMs = AtomicLong(NO_PENDING_SEEK_MS)
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = queueAudioRouteChange()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = queueAudioRouteChange()
        }

    @Volatile
    private var released = false

    @Volatile
    private var activeChild: YPlayer? = null

    /**
     * The newest output the caller has set, written on the caller's thread.
     *
     * prepare() normally arrives before the Surface does, and the SetVideoOutput command then waits
     * behind the whole first probe and child construction. A child attached from the command's
     * copy started without a Surface, so NativeDirect configured video only when the command
     * finally ran and had to reposition its extractor for it (1.0.83: a startup seek to 0 followed
     * by an IllegalStateException). The attach hands this to a NativeDirect child instead.
     */
    @Volatile
    private var requestedVideoOutput: YVideoOutput? = null

    private val probes = AndroidProbeController()
    private val activeProbeBudget: AndroidProbeBudget? get() = probes.budget()

    private fun invalidateProbe(reason: String) {
        probes.invalidate(reason)
    }

    private val actualAudioRouteMonitor =
        scope.launch {
            mutableState
                .map { it.diagnostics.audioOutputFingerprint }
                .filter { it.isNotBlank() }
                .distinctUntilChanged()
                .collect { queueAudioRouteChange() }
        }

    init {
        // registerAudioDeviceCallback immediately invokes onAudioDevicesAdded with every output
        // already connected. Against the seeded fingerprint above that is now a no-op; before,
        // merely constructing this player reported a route change, and it arrived while the first
        // graph was being built - which tore that graph down and started over.
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, audioCallbackHandler)
    }

    override fun prepare() {
        if (released) return
        invalidateProbe("superseded")
        send(Command.Prepare)
    }

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released) return false
        requestedVideoOutput = output
        commands.trySend(Command.SetVideoOutput(output))
        return true
    }

    override fun play() {
        if (released) return
        mutableState.updateState { it.copy(playbackRequested = true, error = null, errorCategory = null) }
        commands.trySend(Command.Play)
    }

    override fun pause() {
        if (released) return
        mutableState.updateState { it.copy(playbackRequested = false, playing = false, buffering = false) }
        commands.trySend(Command.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0L)
        mutableState.updateState {
            it.copy(positionMs = target, subtitleCues = emptyList(), secondarySubtitleCues = emptyList())
        }
        pendingSeekMs.set(target)
        queuePendingSeek()
    }

    override fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed <= 0f) return
        mutableState.updateState { it.copy(speed = speed) }
        commands.trySend(Command.SetSpeed(speed))
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) = send(Command.SelectTrack(type, id))

    @Volatile
    private var secondarySubtitleSupported = false

    override val supportsSecondarySubtitleTrack: Boolean get() = secondarySubtitleSupported

    override val supportsSecondarySubtitleOffset: Boolean get() = activeChild?.supportsSecondarySubtitleOffset == true

    override fun setSecondarySubtitleOffsetMs(offsetMs: Long): Boolean {
        if (released || !supportsSecondarySubtitleOffset || offsetMs !in -60_000L..60_000L) return false
        send(Command.SecondarySubtitleOffset(offsetMs))
        return true
    }

    override fun selectSecondarySubtitleTrack(id: String): Boolean {
        if (released || !supportsSecondarySubtitleTrack) return false
        if (id != "off" && mutableState.value.subtitleTracks.none { it.id == id && !it.selected }) return false
        send(Command.SelectSecondarySubtitle(id))
        return true
    }

    private val nextPreparationBoundary = AtomicReference<NextItemPreparationBoundary?>(null)
    private val nextPreparationRevision = AtomicLong()
    private val nextItemNetworkGate =
        NextItemNetworkGate { allowMeteredNetwork -> nextItemNetworkAllowed(context, allowMeteredNetwork) }

    override fun setNextItemPreparation(
        itemId: String,
        transitionPositionMs: Long?,
        enabled: Boolean,
        allowMeteredNetwork: Boolean,
        nextIntroEndMs: Long?,
    ) {
        if (released) return
        val next =
            NextItemPreparationBoundary(
                itemId = itemId,
                positionMs = transitionPositionMs?.takeIf { it > 0L },
                enabled = enabled,
                allowMeteredNetwork = allowMeteredNetwork,
                nextIntroEndMs = nextIntroEndMs?.takeIf { it > 0L },
            )
        if (nextPreparationBoundary.getAndSet(next) != next) {
            nextPreparationRevision.incrementAndGet()
            commands.trySend(Command.PreparationBoundaryChanged)
        }
    }

    override fun selectItem(index: Int) {
        if (released || index !in queueItems.indices) return
        invalidateProbe("superseded")
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                playing = false,
                buffering = it.playbackRequested,
                positionMs = 0L,
                currentIndex = index,
                error = null,
                errorCategory = null,
            )
        }
        commands.trySend(Command.SelectItem(queueItems[index].id))
    }

    override fun selectDiscTitle(index: Int): Boolean = !released && activeChild?.selectDiscTitle(index) == true

    override fun selectDiscChapter(index: Int): Boolean = !released && activeChild?.selectDiscChapter(index) == true

    override fun selectDiscAngle(index: Int): Boolean = !released && activeChild?.selectDiscAngle(index) == true

    override fun sendDiscMenuCommand(command: com.yfuse.core.playback.PlaybackDiscMenuCommand): Boolean =
        !released && activeChild?.sendDiscMenuCommand(command) == true

    override fun appendItems(items: List<YMediaItem>): Boolean =
        synchronized(queueLock) {
            if (released) return@synchronized false
            val previous = queueItems
            val extended = previous.appendingDistinct(items) ?: return@synchronized false
            if (extended === previous) return@synchronized true
            queueItems = extended
            if (commands.trySend(Command.QueueExtended).isSuccess) {
                true
            } else {
                queueItems = previous
                false
            }
        }

    override fun updateQueue(
        items: List<YMediaItem>,
        currentIndex: Int,
    ): Boolean =
        synchronized(queueLock) {
            if (released ||
                activeChild == null ||
                mutableState.value.phase != YPlaybackPhase.Ready
            ) {
                return@synchronized false
            }
            val oldItems = queueItems
            val current = oldItems.getOrNull(mutableState.value.currentIndex) ?: return@synchronized false
            val replacement = items.getOrNull(currentIndex) ?: return@synchronized false
            // Metadata may arrive later, but an update must never replace an active byte source.
            if (current.id != replacement.id ||
                current.uri != replacement.uri ||
                current.headers != replacement.headers ||
                current.drmConfiguration != replacement.drmConfiguration ||
                current.transportCredentials != replacement.transportCredentials
            ) {
                return@synchronized false
            }
            if (items.map { it.id }.distinct().size != items.size) return@synchronized false
            queueItems = items
            if (!commands.trySend(Command.QueueUpdated).isSuccess) {
                queueItems = oldItems
                return@synchronized false
            }
            mutableState.update { it.copy(currentIndex = currentIndex, itemCount = items.size) }
            true
        }

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override val supportsAudioDelay: Boolean get() = true

    override fun setAudioDelayMs(delayMs: Long): Boolean {
        if (released) return false
        commands.trySend(Command.SetAudioDelay(delayMs.coerceIn(-5_000L, 5_000L)))
        return true
    }

    override fun retry() {
        if (released) return
        invalidateProbe("superseded")
        send(Command.Retry)
    }

    override fun release() {
        if (released) return
        released = true
        invalidateProbe("released")
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        spatialAudioStateMonitor?.release()
        commands.close()
        worker.cancel()
        scope.cancel()
        runCatching(onRelease)
        mutableState.update { current ->
            current.copy(
                phase = YPlaybackPhase.Idle,
                playing = false,
                playbackRequested = false,
                buffering = false,
            )
        }
    }

    override val releaseCompleted: Boolean
        get() = released && worker.isCompleted && releaseBarrier.idle

    /**
     * [release] only asks the worker to stop; the child decoder it owned is released from the
     * worker's `finally` without being awaited. Joining both here is what lets a replacement
     * engine allocate its own MediaCodec without overlapping the outgoing one.
     */
    override suspend fun releaseAndJoin() {
        release()
        val completed =
            withContext(NonCancellable) {
                withTimeoutOrNull(RELEASE_JOIN_TIMEOUT_MS) {
                    worker.join()
                    releaseBarrier.await()
                    true
                }
            }
        check(completed == true) {
            "YCore 2.0 router did not finish releasing its decoder; replacement was not started"
        }
    }

    private fun send(command: Command) {
        if (!released) commands.trySend(command)
    }

    /**
     * Comparable identity of everything that decides where decoded audio actually goes.
     *
     * Both sources feeding [queueAudioRouteChange] are broad: AudioDeviceCallback fires for any
     * device appearing or disappearing anywhere in the system, and the spatializer listener says
     * only that spatial audio changed somehow. Neither says whether *this* playback's output
     * moved, and creating an AudioTrack alone produces several of them. Comparing this value is
     * what separates a real route change from that noise.
     */
    private fun currentAudioOutputFingerprint(): String {
        val routed =
            activeChild
                ?.state
                ?.value
                ?.diagnostics
                ?.audioOutputFingerprint
                .orEmpty()
        if (routed.isBlank()) return ""
        val outputs =
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map {
                it.playbackCapabilityFingerprint()
            }
        return activeAudioOutputFingerprint(routed, outputs, androidSpatialAudioFingerprint(context))
    }

    private fun queueAudioRouteChange() {
        if (released) return
        val fingerprint = currentAudioOutputFingerprint()
        if (fingerprint.isBlank()) return
        synchronized(audioOutputFingerprintLock) {
            if (fingerprint == observedAudioOutputFingerprint) return
            if (observedAudioOutputFingerprint.isBlank()) {
                observedAudioOutputFingerprint = fingerprint
                return
            }
            observedAudioOutputFingerprint = fingerprint
        }
        if (audioRouteChangeQueued.compareAndSet(false, true)) {
            if (commands.trySend(Command.AudioRouteChanged).isFailure) {
                audioRouteChangeQueued.set(false)
            }
        }
    }

    private fun queuePendingSeek() {
        if (!released && seekCommandQueued.compareAndSet(false, true)) {
            if (commands.trySend(Command.SeekPending).isFailure) {
                seekCommandQueued.set(false)
            }
        }
    }

    private fun currentBatteryPermille(): Int {
        val percent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return -1
        return if (percent in 0..100) percent * 10 else -1
    }

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager?.currentThermalStatus ?: 0
        } else {
            0
        }

    private suspend fun runLoop() {
        var currentIndex by AndroidQueueCursor(request.items[request.startIndex].id) { queueItems }
        var child: YPlayer? = null
        var secondarySubtitleOffsetMs = 0L
        var childCollector: Job? = null
        var output: YVideoOutput? = null

        /** The output the active child was last given, so a queued duplicate is not re-applied. */
        var childVideoOutput: YVideoOutput? = null
        var requestedPlay = request.autoPlay
        var speed = 1f
        var audioDelayMs = 0L
        var adaptiveTarget: YAdaptivePlaybackTarget? = null
        var pendingAdaptiveTarget: YAdaptivePlaybackTarget? = null
        var pausedSeekPreviewRequested = false

        fun globalChildPosition(): Long =
            (child?.currentPositionMs() ?: 0L) + (adaptiveTarget?.presentationOffsetMs ?: 0L)
        var pendingPositionMs = request.startPositionMs
        var allowTunnel = true
        val runtimeTrackSelections =
            request.items
                .mapNotNull { item ->
                    item.initialTrackSelection?.orNull()?.let { item.id to it }
                }.toMap()
                .toMutableMap()
        val loggedTrackSkips = mutableSetOf<String>()
        var forceEnhancedFallback = false
        var forceSoftwareFallback = false
        var bypassLearnedRouteMemoryOnce = false
        var pendingFailureKey: YCore2FailureKey? = null

        /** Dynamic range entering the child being started; decides whether software can take over. */
        var pendingInputHdrType: YHdrType? = null

        /** The media and probe behind the child being started, recorded once the child renders. */
        var pendingVerifiedRoute: Pair<YMediaItem, YCore2ProbeResult.Success>? = null
        var finalizeChildLearning: (() -> Unit)? = null
        val videoHandoff = AndroidVideoDecoderHandoff()
        var nextItemPreloadJob: Job? = null
        var preloadedNextRoute: PreloadedNextRoute? = null
        var nextPreloadRetryAfterMs = 0L
        var handoffStartedNs: Long? = null
        var handoffItemId: String? = null
        var handoffVideoLogged = false
        var handoffAudioLogged = false

        fun discardNextPreparation() {
            nextPreparationRevision.incrementAndGet()
            nextItemPreloadJob?.cancel()
            nextItemPreloadJob = null
            preloadedNextRoute?.sources?.close()
            preloadedNextRoute = null
        }
        val adaptiveFeedbackGeneration = AtomicLong(0L)
        val sameRouteRecoveryAttempts = mutableMapOf<RouteRecoveryKey, Int>()
        val codecResetCounts = mutableMapOf<Int, Int>()

        suspend fun stopChild(waitForRelease: Boolean = true) {
            nextItemPreloadJob?.cancel()
            nextItemPreloadJob = null
            finalizeChildLearning?.invoke()
            finalizeChildLearning = null
            childCollector?.cancel()
            childCollector = null
            val previous = child
            child = null
            secondarySubtitleSupported = false
            activeChild = null
            previous?.release()
            if (previous is AndroidSerializedPlayerRelease) releaseBarrier.retire(previous)
            // A timed-out release remains a barrier for every later Retry. Clearing the visible
            // child cannot grant another codec while its old owner is still tearing down.
            if (waitForRelease) releaseBarrier.await()
        }

        /**
         * The last concrete failure a route of the current start published.
         *
         * Every rebuild clears the visible error, so without this a recovery that could not
         * finish published whatever the router itself hit last. In 1.0.83 NativeDirect failed with
         * a concrete Demux failure, the enhanced recovery died on the spent start deadline, and
         * the user read "片源起播探测超时，请检查网络" (Network) about media that never had a
         * network problem.
         */
        var keptRouteFailure: YCoreRouteFailure? = null

        suspend fun publishUnavailable(
            reason: String,
            sourceFailure: YPlaybackException? = null,
            probeTimedOut: Boolean = false,
        ) {
            stopChild(waitForRelease = false)
            val item = queueItems[currentIndex]
            mutableState.updateState {
                val published =
                    yCoreUnavailableError(
                        routerReason = reason,
                        sourceFailure = sourceFailure,
                        keptFailure = keptRouteFailure,
                        protectedContent = item.drmConfiguration != null,
                        nativeOnly = nativeOnly,
                        probeTimedOut = probeTimedOut,
                        currentError = it.error,
                        currentCategory = it.errorCategory,
                    )
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = requestedPlay,
                    buffering = false,
                    error = published.message,
                    errorCategory = published.category,
                    diagnostics =
                        it.diagnostics.copy(
                            route =
                                if (nativeOnly) {
                                    it.diagnostics.route
                                } else {
                                    YPlaybackRoute.Legacy
                                },
                            reason = published.reason,
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                        ),
                )
            }
        }

        /** A deadline this router renewed for recovery, and when it really ends (see below). */
        var renewedProbeBudget: AndroidProbeBudget? = null
        var renewedProbeDeadlineNs = 0L

        fun remainingStartDeadlineMs(budget: AndroidProbeBudget): Long {
            val remainingMs = budget.remainingMsOrZero()
            if (budget !== renewedProbeBudget) return remainingMs
            val renewedRemainingNs = (renewedProbeDeadlineNs - System.nanoTime()).coerceAtLeast(0L)
            return minOf(remainingMs, renewedRemainingNs / NANOS_PER_MILLISECOND)
        }

        /**
         * Makes sure a recovery attempt of a start that has not rendered yet has time to run.
         *
         * One deadline used to cover a start and every recovery tier after it. In 1.0.83
         * NativeDirect failed at 32 s with a concrete Demux failure, the policy chose the enhanced
         * route, and the rebuild died at its first ensureActive() on the spent start budget, so
         * NativeEnhanced was never tried. The failed route has just read this source, so an
         * attempt with less than [RECOVERY_PROBE_ALLOWANCE_MS] left gets that much of its own;
         * one with more keeps what it has. A start that already rendered has no active budget,
         * and its rebuild begins a full one as before.
         */
        fun ensureRecoveryProbeBudget() {
            val current = activeProbeBudget ?: return
            val remainingMs = remainingStartDeadlineMs(current)
            if (!yCoreRecoveryNeedsFreshProbeBudget(remainingMs)) return
            probes.invalidate("recovery")
            val renewed = probes.begin().budget
            // The controller's budgets carry the full start deadline; this bounds the attempt.
            renewedProbeBudget = renewed
            renewedProbeDeadlineNs = System.nanoTime() + RECOVERY_PROBE_ALLOWANCE_MS * NANOS_PER_MILLISECOND
            scope.launch {
                delay(RECOVERY_PROBE_ALLOWANCE_MS)
                renewed.cancel("deadline")
            }
            AppLog.info(
                category = "player.core2",
                event = "recovery_probe_budget_renewed",
                message = "YCore gave a recovery attempt its own startup deadline",
                attributes =
                    mapOf(
                        "allowanceMs" to RECOVERY_PROBE_ALLOWANCE_MS.toString(),
                        "previousRemainingMs" to remainingMs.toString(),
                        "generation" to probes.generation().toString(),
                    ),
            )
        }

        fun scheduleNextItemPreload(fromIndex: Int) {
            if (!request.autoNext || fromIndex != currentIndex) return
            val preloadChild = child ?: return
            val currentItem = queueItems.getOrNull(fromIndex) ?: return
            val nextIndex = fromIndex + 1
            val item = queueItems.getOrNull(nextIndex) ?: return
            val hint = nextPreparationBoundary.get()?.takeIf { it.itemId == currentItem.id }
            if (hint?.enabled == false || !nextItemSourceEligible(item)) return
            val nowMs = System.nanoTime() / 1_000_000L
            val forcePowerSaver = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            val preferTunnel =
                !anime4KRequestedFor(item) &&
                    item.allExternalSubtitles.isEmpty() &&
                    audioDelayMs == 0L &&
                    kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            if (nextItemPreloadJob?.isActive == true ||
                nowMs < nextPreloadRetryAfterMs ||
                preloadedNextRoute?.matches(nextIndex, item, preferTunnel, allowAudioPassthrough, forcePowerSaver) ==
                true
            ) {
                return
            }
            preloadedNextRoute?.sources?.close()
            preloadedNextRoute = null
            nextPreloadRetryAfterMs = nowMs + 10_000L
            val preparationRevision = nextPreparationRevision.get()
            nextItemPreloadJob =
                scope.launch(Dispatchers.IO) {
                    val sources = AndroidNextItemSources()
                    var transferred = false
                    val preloadEvaluator = routeEvaluator.newPreparationEvaluator()
                    val preloadPassthrough = allowAudioPassthrough

                    fun snapshot(): YPlayerState? = activeChild?.takeIf { it === preloadChild }?.state?.value

                    fun hint(): NextItemPreparationBoundary? =
                        nextPreparationBoundary.get()?.takeIf { it.itemId == currentItem.id }

                    fun boundary(): Long? = hint()?.positionMs

                    fun allowed(): Boolean =
                        !released &&
                            activeChild === preloadChild &&
                            nextPreparationRevision.get() == preparationRevision &&
                            hint()?.enabled != false &&
                            currentThermalStatus() < SEVERE_THERMAL_STATUS &&
                            nextItemNetworkAllowed(context, hint()?.allowMeteredNetwork == true)

                    fun healthy(): Boolean = allowed() && snapshot()?.let(::nextItemPlaybackHealthy) == true
                    try {
                        if (!awaitNextItemBoundary(
                                90_000L,
                                ::boundary,
                                ::snapshot,
                                ::allowed,
                                stableMs = 5_000L,
                            )
                        ) {
                            return@launch
                        }
                        // The opening prefix (up to 12 MB) is a Wi-Fi convenience; on mobile data the
                        // extractor preparation below reads only what the start itself needs.
                        if (nextItemNetworkAllowed(context)) {
                            speculativeNextItemWork(
                                ::healthy,
                            ) { budget ->
                                warmNextItemBytes(
                                    context.cacheDir,
                                    item,
                                    budget,
                                    shouldContinue = { nextItemNetworkAllowed(context) },
                                )
                            }
                        }
                        // Sources have a 30s lease. Open them only close to credits/natural end.
                        if (!awaitNextItemBoundary(20_000L, ::boundary, ::snapshot, ::allowed)) return@launch
                        val decision =
                            speculativeNextItemWork({
                                healthy() &&
                                    (snapshot()?.let { nextItemRemainingMs(it, boundary()) } ?: Long.MAX_VALUE) <=
                                    25_000L
                            }) { budget ->
                                preloadEvaluator.evaluate(
                                    item,
                                    preferTunnel,
                                    preloadPassthrough,
                                    forcePowerSaver,
                                    prepareSourceForPlayback = true,
                                    budget = budget,
                                )
                            } ?: return@launch
                        currentCoroutineContext().ensureActive()
                        if (!healthy()) return@launch
                        var preparedExtractor = preloadEvaluator.takePreparedExtractor(item)
                        val introEndMs = hint()?.nextIntroEndMs
                        if (preparedExtractor != null &&
                            introEndMs != null &&
                            !warmNextItemIntroEnd(preparedExtractor, introEndMs, ::healthy)
                        ) {
                            preparedExtractor.release()
                            preparedExtractor = null
                        }
                        preparedExtractor?.let { sources.extractor.offer(item, it) }
                        preloadEvaluator.takePreparedEnhancedDemux(item)?.let { sources.enhanced.offer(item, it) }
                        val route =
                            PreloadedNextRoute(
                                nextIndex,
                                item,
                                preferTunnel,
                                preloadPassthrough,
                                forcePowerSaver,
                                decision,
                                sources,
                            )
                        transferred =
                            commands
                                .trySend(
                                    Command.NextItemPreloaded(preloadChild, route, preparationRevision),
                                ).isSuccess
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        AppLog.warning(
                            category = "player.core2",
                            event = "next_item_preload_failed",
                            message = "Optional next-item preparation stopped; normal open remains available",
                            throwable = error,
                            attributes = mapOf("itemIndex" to nextIndex.toString()),
                        )
                    } finally {
                        preloadEvaluator.closePreparedExtractor()
                        preloadEvaluator.closePreparedEnhancedDemux()
                        if (!transferred) sources.close()
                    }
                }
        }

        fun createInternalEnhancedRoute(
            item: YMediaItem,
            singleRequest: YPlayerOpenRequest,
            decision: YCore2RouteDecision?,
        ): YPlayer? {
            if (item.drmConfiguration != null) return null
            val inputHdrType =
                decision
                    ?.probe
                    ?.playbackRequest
                    ?.video
                    ?.hdrType
                    ?: item.hintedHdrType()
            AppLog.info(
                category = "player.core2",
                event = "internal_route_attempt",
                message = "YCore is attempting enhanced demux with hardware decode",
                attributes =
                    mapOf(
                        "route" to YPlaybackRoute.NativeEnhanced.name,
                        "inputHdr" to inputHdrType.name,
                    ),
            )
            return AndroidNativeEnhancedYPlayer(
                context = context,
                request = singleRequest,
                routeEvaluator = routeEvaluator,
                initialDecision = decision,
                initialProbeBudget = activeProbeBudget,
                allowAudioPassthrough = allowAudioPassthrough,
                frameRateSwitchMode = frameRateSwitchMode,
                forcedPlan =
                    yCoreInternalEnhancedRecoveryPlan(
                        inputHdrType = inputHdrType,
                        decoderName = decision?.plan?.decoderName,
                        audioPath = decision?.plan?.audioPath ?: YAudioOutputPath.DecodePcm,
                    ),
                requireDolbyVisionIdentity = inputHdrType == YHdrType.DolbyVision,
                preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
            )
        }

        fun createInconclusiveSourceRoute(
            item: YMediaItem,
            singleRequest: YPlayerOpenRequest,
            decision: YCore2RouteDecision? = null,
        ): YPlayer? {
            val compatibility = fallbackRouteFactory
            if (compatibility != null) {
                return compatibility.create(
                    item = item,
                    request = singleRequest,
                    plan = yCoreInconclusiveSourceCompatibilityPlan(item),
                    startSpeed = speed,
                )
            }
            if (item.drmConfiguration != null) return null
            val preservedProbe =
                decision?.probe
                    ?: if (nativeOnly) {
                        routeEvaluator.probePlatformForNativeAttempt(item, activeProbeBudget)
                    } else {
                        null
                    }
            if (preservedProbe?.requiresEnhancedAudioDemux(item.sourceHints?.audioTrackCount ?: 0) == true) {
                AppLog.info(
                    category = "player.core2",
                    event = "platform_audio_demux_skipped",
                    message = "YCore skipped platform-direct because the probe did not expose required audio",
                    attributes = mapOf("route" to YPlaybackRoute.NativeEnhanced.name),
                )
                return createInternalEnhancedRoute(item, singleRequest, decision)
            }
            AppLog.info(
                category = "player.core2",
                event = "internal_route_attempt",
                message = "YCore is attempting its platform-direct route",
                attributes =
                    mapOf(
                        "route" to YPlaybackRoute.NativeDirect.name,
                        "reason" to "inconclusive_probe",
                    ),
            )
            val probeHdrType =
                preservedProbe
                    ?.playbackRequest
                    ?.video
                    ?.hdrType
            return AndroidNativeDirectYPlayer(
                context = context,
                request = singleRequest,
                decoderName = decision?.plan?.decoderName,
                runtimeCapabilityKey = decision?.runtimeCapabilityKey(),
                plannedAudioOutputPath = decision?.plan?.audioPath,
                preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                preparedExtractor = routeEvaluator::takePreparedExtractor,
                frameRateSwitchMode = frameRateSwitchMode,
                plannedDolbyVisionConfig = preservedProbe?.dolbyVisionConfig,
                confirmedDolbyVisionNalIdentity =
                    preservedProbe?.unconfiguredDolbyVisionSignal == true,
                requireDolbyVisionIdentity =
                    probeHdrType == YHdrType.DolbyVision ||
                        (decision == null && item.hintedHdrType() == YHdrType.DolbyVision),
            )
        }

        fun createInternalSoftwareRoute(
            item: YMediaItem,
            singleRequest: YPlayerOpenRequest,
            decision: YCore2RouteDecision?,
        ): YPlayer? {
            if (item.drmConfiguration != null) return null
            val inputHdrType =
                decision
                    ?.probe
                    ?.playbackRequest
                    ?.video
                    ?.hdrType
                    ?: item.hintedHdrType()
            val plan = yCoreInternalSoftwareRecoveryPlan(inputHdrType)
            if (plan == null || !yCoreSoftwarePlanExecutable(plan)) {
                AppLog.warning(
                    category = "player.core2",
                    event = "internal_route_unavailable",
                    message = "YCore software recovery is unavailable for the current source",
                    attributes =
                        mapOf(
                            "route" to YPlaybackRoute.SoftwareFallback.name,
                            "inputHdr" to inputHdrType.name,
                            "dolbyGuard" to (inputHdrType == YHdrType.DolbyVision).toString(),
                        ),
                )
                return null
            }
            AppLog.info(
                category = "player.core2",
                event = "internal_route_attempt",
                message = "YCore is attempting its software decode route",
                attributes =
                    mapOf(
                        "route" to YPlaybackRoute.SoftwareFallback.name,
                        "inputHdr" to inputHdrType.name,
                    ),
            )
            return AndroidNativeEnhancedYPlayer(
                context = context,
                request = singleRequest,
                routeEvaluator = routeEvaluator,
                initialDecision = decision,
                initialProbeBudget = activeProbeBudget,
                allowAudioPassthrough = false,
                frameRateSwitchMode = frameRateSwitchMode,
                forcedPlan = plan,
                preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
            )
        }

        suspend fun createChild(
            positionMs: Long,
            budget: AndroidProbeBudget,
        ): YPlayer? {
            currentCoroutineContext().ensureActive()
            budget.ensureActive()
            pendingFailureKey = null
            pendingVerifiedRoute = null
            pendingInputHdrType = null
            val bypassLearnedRouteMemory =
                shouldBypassLearnedYCoreRouteMemory(
                    manualRetry = bypassLearnedRouteMemoryOnce,
                    compatibilityRouteAvailable = fallbackRouteFactory != null,
                )
            bypassLearnedRouteMemoryOnce = false
            val queueItem = queueItems[currentIndex]
            val rootItem =
                runtimeTrackSelections[queueItem.id]?.let { queueItem.copy(initialTrackSelection = it) }
                    ?: queueItem
            val target =
                pendingAdaptiveTarget?.takeIf { it.rootUri == rootItem.uri }
                    ?: budget.await {
                        adaptiveFeedbackSink?.resolvePlaybackTarget(rootItem.uri, positionMs.coerceAtLeast(0L))
                    }
            pendingAdaptiveTarget = null
            adaptiveTarget = target
            val item = target?.let { rootItem.copy(uri = it.uri) } ?: rootItem
            pendingInputHdrType = item.hintedHdrType()
            val forcePowerSaver = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            val tunnelAllowed =
                allowTunnel &&
                    !anime4KRequestedFor(item) &&
                    audioDelayMs == 0L &&
                    item.drmConfiguration == null &&
                    item.allExternalSubtitles.isEmpty() &&
                    item.initialTrackSelection?.subtitle == null &&
                    kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            val singleRequest =
                YPlayerOpenRequest(
                    items = listOf(item),
                    startIndex = 0,
                    startPositionMs = target?.localPositionMs ?: positionMs.coerceAtLeast(0L),
                    autoPlay = requestedPlay,
                    autoNext = false,
                )
            if (item.disc != null) {
                return discRouteFactory?.create(
                    item = item,
                    request = singleRequest,
                    startSpeed = speed,
                    forceSoftwareDecode = forceSoftwareFallback || preferSoftwareDecode,
                )
            }
            val warmedRoute =
                preloadedNextRoute?.takeIf { warmed ->
                    target == null &&
                        positionMs == 0L &&
                        warmed.matches(
                            index = currentIndex,
                            item = item,
                            preferTunnel = tunnelAllowed,
                            allowAudioPassthrough = allowAudioPassthrough,
                            forcePowerSaver = forcePowerSaver,
                        )
                }
            if (warmedRoute == null) videoHandoff.close()
            if (warmedRoute != null) {
                preloadedNextRoute = null
                val adopted = routeEvaluator.adoptPreparedSources(item, warmedRoute.sources)
                AppLog.info(
                    category = "player.core2",
                    event = "next_item_source_adopted",
                    message = "Next-item prepared source ownership resolved",
                    attributes = mapOf("adopted" to adopted.toString(), "itemIndex" to currentIndex.toString()),
                )
                AppLog.info(
                    category = "player.core2",
                    event = "next_item_preload_reused",
                    message = "YCore reused the preheated route and decoder capability",
                    attributes = mapOf("itemIndex" to currentIndex.toString()),
                )
            }
            // A route this device already rendered for exactly this media skips the probes and
            // starts at planning; the device-side gates below still run on it.
            val rememberedProbe =
                if (target == null &&
                    warmedRoute == null &&
                    !routeEvaluator.adoptCurrentItem(item, positionMs) &&
                    !bypassLearnedRouteMemory &&
                    !forceSoftwareFallback
                ) {
                    verifiedRouteMemory.probeFor(item)
                } else {
                    null
                }
            if (rememberedProbe != null) {
                AppLog.info(
                    category = "player.core2",
                    event = "verified_route_reused",
                    message = "YCore reused the probe a verified playback of this media established",
                    attributes = mapOf("itemIndex" to currentIndex.toString()),
                )
            }
            var decision =
                warmedRoute?.decision
                    ?: routeEvaluator.evaluate(
                        item,
                        preferTunnel = tunnelAllowed,
                        allowAudioPassthrough = allowAudioPassthrough,
                        forcePowerSaver = forcePowerSaver,
                        rememberedProbe = rememberedProbe,
                        budget = budget,
                    )
            currentCoroutineContext().ensureActive()
            budget.ensureActive()
            decision?.let { pendingInputHdrType = it.probe.playbackRequest.video.hdrType }
            if (forceSoftwareFallback) {
                videoHandoff.close()
                routeEvaluator.closePreparedExtractor()
                return createInternalSoftwareRoute(item, singleRequest, decision)
                    ?: fallbackRouteFactory?.create(
                        item,
                        singleRequest,
                        yCoreInconclusiveSourceCompatibilityPlan(item),
                        speed,
                    )
            }
            if (forceEnhancedFallback) {
                videoHandoff.close()
                routeEvaluator.closePreparedExtractor()
                return createInternalEnhancedRoute(item, singleRequest, decision)
                    ?: createInternalSoftwareRoute(item, singleRequest, decision)
            }
            if (decision == null) return createInconclusiveSourceRoute(item, singleRequest)
            if (
                !bypassLearnedRouteMemory &&
                !forceSoftwareFallback &&
                decision.plan.route == YPlaybackRoute.NativeTunnel &&
                (
                    failureLedger.isBlocked(decision.toFailureKey()) ||
                        learningEngine.advice(decision.toFailureKey().toLearningKey()) !=
                        YLearnedRouteAdvice.Allow
                )
            ) {
                decision =
                    routeEvaluator.evaluate(
                        item,
                        preferTunnel = false,
                        allowAudioPassthrough = allowAudioPassthrough,
                        forcePowerSaver = forcePowerSaver,
                        rememberedProbe = rememberedProbe,
                        budget = budget,
                    ) ?: return createInconclusiveSourceRoute(item, singleRequest)
            }
            currentCoroutineContext().ensureActive()
            budget.ensureActive()
            val learnedAdvice = learningEngine.advice(decision.toFailureKey().toLearningKey())
            if (
                !bypassLearnedRouteMemory &&
                (
                    failureLedger.isBlocked(decision.toFailureKey()) ||
                        learnedAdvice == YLearnedRouteAdvice.Avoid
                )
            ) {
                decision =
                    decision.copy(
                        plan =
                            decision.plan.toSoftwareFallbackPlan(
                                "Device failure and quality memory skipped the planned local route",
                            ),
                    )
            }
            if (!bypassLearnedRouteMemory && failureLedger.isBlocked(decision.toFailureKey())) return null
            val plan = decision.plan
            if (!decision.nativeDirectExecutable) routeEvaluator.closePreparedExtractor()
            if (!decision.nativeDirectExecutable ||
                decision.probe.playbackRequest.video.hdrType != YHdrType.Sdr ||
                decision.plan.audioPath != YAudioOutputPath.DecodePcm
            ) {
                videoHandoff.close()
            }
            AppLog.info(
                category = "player.core2",
                event = "route_selected",
                message =
                    "YCore selected ${plan.route.name} playback graph " +
                        "(${playbackDiagnosticTrace(item.playbackSessionId)})",
                attributes =
                    mapOf(
                        "itemId" to item.id,
                        "serverId" to item.providerKey.orEmpty(),
                        "sessionId" to item.playbackSessionId.orEmpty(),
                        "playbackTrace" to playbackDiagnosticTrace(item.playbackSessionId),
                        "route" to plan.route.name,
                        "demuxPath" to plan.demuxPath.name,
                        "decodePath" to plan.decodePath.name,
                        "renderPath" to plan.renderPath.name,
                        "platformDemuxSupported" to
                            decision.probe.playbackRequest.platformDemuxSupported
                                .toString(),
                        "enhancedDemuxSupported" to
                            decision.probe.playbackRequest.enhancedDemuxSupported
                                .toString(),
                        "videoCodec" to decision.probe.playbackRequest.video.codec.name,
                        "audioCodec" to
                            (
                                decision.probe.playbackRequest.audio
                                    ?.codec
                                    ?.name ?: "None"
                            ),
                    ),
            )
            pendingFailureKey = decision.toFailureKey()
            // Manifest target revisions may carry a different init under the same user cache identity.
            // Each revision is probed afresh and must never poison the root item's learned probe.
            pendingVerifiedRoute = if (target == null) item to decision.probe else null
            return when {
                !forceSoftwareFallback && tunnelAllowed && decision.nativeTunnelExecutable ->
                    AndroidNativeTunnelYPlayer(
                        context = context,
                        request = singleRequest,
                        routeEvaluator = routeEvaluator,
                        initialDecision = decision,
                        allowAudioPassthrough = allowAudioPassthrough,
                        frameRateSwitchMode = frameRateSwitchMode,
                    )
                !forceSoftwareFallback &&
                    decision.nativeDirectExecutable &&
                    !decision.plan.usesHdrFallback ->
                    AndroidNativeDirectYPlayer(
                        context = context,
                        request = singleRequest,
                        decoderName = decision.plan.decoderName,
                        runtimeCapabilityKey = decision.runtimeCapabilityKey(),
                        plannedAudioOutputPath = decision.plan.audioPath,
                        preparedExtractor = routeEvaluator::takePreparedExtractor,
                        videoHandoff =
                            videoHandoff.takeIf {
                                item.drmConfiguration == null &&
                                    decision.probe.playbackRequest.video.hdrType == YHdrType.Sdr &&
                                    decision.plan.audioPath == YAudioOutputPath.DecodePcm
                            },
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                        frameRateSwitchMode = frameRateSwitchMode,
                        plannedDolbyVisionConfig = decision.probe.dolbyVisionConfig,
                        confirmedDolbyVisionNalIdentity =
                            decision.probe.unconfiguredDolbyVisionSignal,
                        requireDolbyVisionIdentity =
                            decision.probe.playbackRequest.video.hdrType == YHdrType.DolbyVision,
                    )
                !forceSoftwareFallback && decision.nativeEnhancedExecutable ->
                    AndroidNativeEnhancedYPlayer(
                        context = context,
                        request = singleRequest,
                        routeEvaluator = routeEvaluator,
                        initialDecision = decision,
                        initialProbeBudget = activeProbeBudget,
                        allowAudioPassthrough = allowAudioPassthrough,
                        frameRateSwitchMode = frameRateSwitchMode,
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                    )
                // The enhanced session is built around a video track and a valid Surface.
                plan.route == YPlaybackRoute.SoftwareFallback &&
                    !decision.audioOnly &&
                    plan.demuxPath == YDemuxPath.Enhanced &&
                    item.drmConfiguration == null &&
                    yCoreSoftwarePlanExecutable(plan) ->
                    AndroidNativeEnhancedYPlayer(
                        context = context,
                        request = singleRequest,
                        routeEvaluator = routeEvaluator,
                        initialDecision = decision,
                        initialProbeBudget = activeProbeBudget,
                        allowAudioPassthrough = false,
                        frameRateSwitchMode = frameRateSwitchMode,
                        forcedPlan = plan,
                        // Only hardware video decode reaches here with Dolby Vision (see the
                        // guard); the demux must then still prove the Dolby configuration.
                        requireDolbyVisionIdentity = plan.inputHdrType == YHdrType.DolbyVision,
                        preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                    )
                plan.route == YPlaybackRoute.GpuEnhanced -> {
                    val routeGpuProbe =
                        AndroidYCoreGpuRuntime.probe(
                            context,
                            yCoreGpuEvidenceKey(decision.probe.playbackRequest, plan),
                        )
                    if (
                        routeGpuProbe.canAttemptNativeVulkan &&
                        decision.probe.playbackRequest.enhancedDemuxSupported &&
                        item.drmConfiguration == null
                    ) {
                        val nativeGpuPlan =
                            plan.copy(
                                demuxPath = YDemuxPath.Enhanced,
                                reason =
                                    buildString {
                                        append(plan.reason)
                                        if (plan.demuxPath != YDemuxPath.Enhanced) {
                                            append("; Vulkan frame ownership requires YCore enhanced demux")
                                        }
                                        append(
                                            if (routeGpuProbe.canClaimNativeVulkan) {
                                                "; native Vulkan output passed the persisted measurement gate"
                                            } else {
                                                "; native Vulkan measurement trial (libplacebo remains recovery)"
                                            },
                                        )
                                    },
                            )
                        AndroidNativeEnhancedYPlayer(
                            context = context,
                            request = singleRequest,
                            routeEvaluator = routeEvaluator,
                            initialDecision = decision,
                            initialProbeBudget = activeProbeBudget,
                            allowAudioPassthrough = false,
                            frameRateSwitchMode = frameRateSwitchMode,
                            forcedPlan = nativeGpuPlan,
                            preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
                        )
                    } else {
                        fallbackRouteFactory?.create(
                            item,
                            singleRequest,
                            plan.withNativeGpuFallbackTruth(routeGpuProbe),
                            speed,
                        ) ?: createInconclusiveSourceRoute(item, singleRequest, decision)
                    }
                }
                plan.route == YPlaybackRoute.SoftwareFallback ->
                    fallbackRouteFactory?.create(item, singleRequest, plan, speed)
                        ?: createInternalSoftwareRoute(item, singleRequest, decision)
                        ?: createInconclusiveSourceRoute(item, singleRequest, decision)
                else -> createInconclusiveSourceRoute(item, singleRequest, decision)
            }
        }

        suspend fun attachChild(next: YPlayer) {
            stopChild()
            val attachedTarget = adaptiveTarget
            val attachedProbeBudget = activeProbeBudget
            val attachedFeedbackGeneration = adaptiveFeedbackGeneration.incrementAndGet()
            adaptiveFeedbackSink?.updatePlaybackFeedback(
                YAdaptivePlaybackFeedback(
                    bufferedDurationUs = 0L,
                    playing = false,
                    speed = speed,
                    generation = attachedFeedbackGeneration,
                ),
            )
            child = next
            secondarySubtitleSupported = next.supportsSecondarySubtitleTrack
            val childItemId = queueItems[currentIndex].id
            val childSoftwareFallbackAttempted =
                forceSoftwareFallback || (preferSoftwareDecode && queueItems[currentIndex].disc != null)
            activeChild = next

            fun childIndex(): Int = queueItems.indexOfFirst { it.id == childItemId }.coerceAtLeast(0)
            val childFailureKey = pendingFailureKey
            val childVerifiedRoute = pendingVerifiedRoute
            val childInputHdrType = pendingInputHdrType ?: queueItems[currentIndex].hintedHdrType()
            var failureRecorded = false
            var successRecorded = false
            var learningRecorded = false
            var autoNextQueued = false
            var recoveryQueued = false
            val verifiedRouteSuspicion = YCoreVerifiedRouteSuspicion()

            /** What the latest failure of this child tells the recovery decision. */
            var attemptDeterministic = false
            var attemptRanOutOfTime = false
            val networkRecoveryWindow = AndroidNetworkRecoveryWindow()
            val learningStartPositionMs = next.currentPositionMs() + (attachedTarget?.presentationOffsetMs ?: 0L)
            val learningStartBatteryPermille = currentBatteryPermille()
            val learningStartThermalStatus = currentThermalStatus()

            fun recordLearning(
                childState: YPlayerState,
                terminal: Boolean,
            ) {
                val key = childFailureKey?.toLearningKey() ?: return
                if (learningRecorded) return
                val playedDurationMs =
                    (childState.positionMs - learningStartPositionMs).coerceAtLeast(0L)
                // A normal handover after only a few frames is not a useful quality sample.
                // Failures and naturally-ended short clips remain terminal evidence.
                if (!terminal && playedDurationMs < MIN_LEARNING_PLAYBACK_MS) return
                val endBatteryPermille = currentBatteryPermille()
                learningEngine.record(
                    key = key,
                    observation =
                        YPlaybackObservation(
                            rendered = childState.diagnostics.videoOutputVerified,
                            playedDurationMs = playedDurationMs,
                            droppedFrames = childState.diagnostics.droppedFrames.coerceAtLeast(0),
                            codecResets =
                                maxOf(
                                    childState.diagnostics.codecResetCount +
                                        (codecResetCounts[childIndex()] ?: 0),
                                    if (childState.errorCategory == YPlaybackFailureCategory.Decoder) 1 else 0,
                                ),
                            audioUnderruns =
                                maxOf(
                                    childState.diagnostics.audioUnderrunCount,
                                    if (childState.errorCategory == YPlaybackFailureCategory.AudioSink) 1 else 0,
                                ),
                            maximumAbsoluteAvDriftMs =
                                if (childState.diagnostics.avSyncMeasured) {
                                    kotlin.math.abs(childState.diagnostics.avSyncOffsetMs ?: 0L)
                                } else {
                                    0L
                                },
                            maximumThermalStatus =
                                maxOf(learningStartThermalStatus, currentThermalStatus()),
                            batteryDeltaPermille =
                                if (learningStartBatteryPermille >= 0 && endBatteryPermille >= 0) {
                                    endBatteryPermille - learningStartBatteryPermille
                                } else {
                                    0
                                },
                        ),
                )
                learningRecorded = true
            }

            finalizeChildLearning = {
                recordLearning(next.state.value, terminal = false)
            }

            next.setSpeed(speed)
            next.setAudioDelayMs(audioDelayMs)
            // NativeDirect gets the caller's newest output rather than the command copy, which may
            // still be queued behind this very start (see requestedVideoOutput): before prepare()
            // it only records the Surface and then configures its decoder with it. The enhanced
            // child starts a full prepare for any valid Surface, and the prepare() below would then
            // open the source a second time, so it keeps receiving the output by command.
            val attachedOutput = if (next is AndroidNativeDirectYPlayer) requestedVideoOutput else output
            next.setVideoOutput(attachedOutput)
            childVideoOutput = attachedOutput
            childCollector =
                scope.launch {
                    next.state.collect { localChildState ->
                        if (released || activeChild !== next) return@collect
                        if (localChildState.diagnostics.videoOutputVerified ||
                            localChildState.diagnostics.audioOutputVerified
                        ) {
                            attachedProbeBudget?.let(probes::complete)
                            // An in-place recovery of this child may run on a renewed deadline.
                            activeProbeBudget?.let(probes::complete)
                            keptRouteFailure = null
                        }
                        if (localChildState.diagnostics.videoOutputVerified) verifiedRouteSuspicion.onVideoOutput()
                        if (activeChild !== next) return@collect
                        val reportedChildState = mapAdaptivePresentationState(localChildState, attachedTarget)
                        val nextPeriodPosition =
                            attachedTarget?.periodEndGlobalMs?.takeIf { endMs ->
                                localChildState.phase == YPlaybackPhase.Ended &&
                                    !isPrematurePlaybackEnd(localChildState.positionMs, localChildState.durationMs) &&
                                    attachedTarget.presentationDurationMs > endMs
                            }
                        if (nextPeriodPosition != null && !recoveryQueued) {
                            recoveryQueued = true
                            commands.trySend(Command.AdaptiveTransition(next, nextPeriodPosition, null))
                            return@collect
                        }
                        val reportedBufferedDurationMs =
                            maxOf(
                                reportedChildState.diagnostics.sourceBufferedMs,
                                (reportedChildState.bufferedPositionMs - reportedChildState.positionMs)
                                    .coerceAtLeast(0L),
                            )
                        adaptiveFeedbackSink?.updatePlaybackFeedback(
                            YAdaptivePlaybackFeedback(
                                bufferedDurationUs =
                                    reportedBufferedDurationMs * MICROSECONDS_PER_MILLISECOND,
                                playing = reportedChildState.playing,
                                speed = speed,
                                generation = adaptiveFeedbackGeneration.get(),
                            ),
                        )
                        if (attachedTarget != null &&
                            localChildState.phase == YPlaybackPhase.Ready &&
                            !recoveryQueued
                        ) {
                            val transition =
                                adaptiveFeedbackSink?.pollPlaybackTransition(
                                    attachedTarget.rootUri,
                                    reportedChildState.positionMs,
                                )
                            if (transition != null &&
                                transition.feedbackGeneration == adaptiveFeedbackGeneration.get()
                            ) {
                                recoveryQueued = true
                                commands.trySend(
                                    Command.AdaptiveTransition(next, reportedChildState.positionMs, transition),
                                )
                                return@collect
                            }
                        }
                        val prematureEnd =
                            reportedChildState.phase == YPlaybackPhase.Ended &&
                                isPrematurePlaybackEnd(
                                    positionMs = reportedChildState.positionMs,
                                    durationMs = reportedChildState.durationMs,
                                )
                        val prematureEndRecoveryKey =
                            RouteRecoveryKey(
                                itemIndex = childIndex(),
                                route = reportedChildState.diagnostics.route,
                                category = YPlaybackFailureCategory.Network,
                            )
                        if (networkRecoveryWindow.observe(
                                nowMs = System.nanoTime() / 1_000_000L,
                                positionMs = reportedChildState.positionMs,
                                playing = reportedChildState.playing && !reportedChildState.buffering,
                                speed = speed,
                            )
                        ) {
                            sameRouteRecoveryAttempts.remove(prematureEndRecoveryKey)
                        }
                        if ((nextItemPreloadJob != null || preloadedNextRoute != null) &&
                            (
                                reportedChildState.buffering ||
                                    !nextItemNetworkGate.allowed(
                                        nextPreparationBoundary.get()?.allowMeteredNetwork == true,
                                    )
                            )
                        ) {
                            discardNextPreparation()
                        }
                        val transientNetworkFailure =
                            reportedChildState.phase == YPlaybackPhase.Failed &&
                                reportedChildState.errorCategory == YPlaybackFailureCategory.Network &&
                                reportedChildState.diagnostics.recoverableNetworkFailure
                        if (
                            (prematureEnd || transientNetworkFailure) &&
                            !recoveryQueued &&
                            (sameRouteRecoveryAttempts[prematureEndRecoveryKey] ?: 0) <
                            MAX_CONSECUTIVE_NETWORK_RECOVERY_ATTEMPTS
                        ) {
                            recoveryQueued = true
                            sameRouteRecoveryAttempts[prematureEndRecoveryKey] =
                                (sameRouteRecoveryAttempts[prematureEndRecoveryKey] ?: 0) + 1
                            mutableState.value =
                                reportedChildState.copy(
                                    phase = YPlaybackPhase.Preparing,
                                    playing = false,
                                    playbackRequested = requestedPlay,
                                    buffering = requestedPlay,
                                    diagnostics =
                                        reportedChildState.diagnostics.copy(
                                            reason =
                                                "Remote source ended early; " +
                                                    "reopening from verified output position",
                                        ),
                                )
                            AppLog.warning(
                                category = "player.network",
                                event = if (prematureEnd) "premature_eof_recovery" else "transport_read_recovery",
                                message =
                                    "YCore rejected a premature EOF " +
                                        "and reopened the active route",
                                attributes =
                                    mapOf(
                                        "route" to reportedChildState.diagnostics.route.name,
                                        "itemIndex" to childIndex().toString(),
                                        "positionMs" to reportedChildState.positionMs.toString(),
                                        "durationMs" to reportedChildState.durationMs.toString(),
                                        "attempt" to sameRouteRecoveryAttempts[prematureEndRecoveryKey].toString(),
                                    ),
                            )
                            commands.trySend(
                                Command.RecoverSameRoute(
                                    index = childIndex(),
                                    positionMs = reportedChildState.positionMs,
                                    route = reportedChildState.diagnostics.route,
                                ),
                            )
                            return@collect
                        }
                        val childState =
                            if (prematureEnd) {
                                reportedChildState.copy(
                                    phase = YPlaybackPhase.Failed,
                                    playing = false,
                                    playbackRequested = requestedPlay,
                                    buffering = false,
                                    error = "片源在声明时长前提前结束，已判定为网络传输中断",
                                    errorCategory = YPlaybackFailureCategory.Network,
                                    diagnostics =
                                        reportedChildState.diagnostics.copy(
                                            reason = "Premature EOF remained after bounded transport recovery",
                                        ),
                                )
                            } else {
                                reportedChildState
                            }
                        if (childState.phase != YPlaybackPhase.Failed && recoveryQueued) {
                            // An in-place retry keeps this collector. Give the recovered attempt a
                            // fresh failure edge so a second terminal failure can advance to the
                            // next recovery tier instead of being hidden by the first attempt.
                            failureRecorded = false
                            recoveryQueued = false
                        }
                        if (childState.phase == YPlaybackPhase.Failed && !failureRecorded) {
                            failureRecorded = true
                            val category = childState.errorCategory
                            val executedRoute = childState.diagnostics.route
                            // Filed under the route that ran, not the plan's label: 1.0.83 filed
                            // a NativeEnhanced decoder failure under the SoftwareFallback plan
                            // the Dolby guard had turned away.
                            val executedFailureKey = childFailureKey?.forExecutedRoute(executedRoute)
                            if (executedFailureKey != null && category != null) {
                                failureLedger.recordFailure(executedFailureKey, category)
                            }
                            verifiedRouteSuspicion.onFailure(category)
                            val reportedFailure =
                                if (prematureEnd) null else (next as? YPlaybackFailureReporter)?.lastPlaybackFailure
                            attemptDeterministic = (reportedFailure as? YPlaybackException)?.deterministic == true
                            attemptRanOutOfTime =
                                yCoreAttemptRanOutOfTime(
                                    reported = reportedFailure,
                                    category = category,
                                    attemptDeadlineStopped = activeProbeBudget?.hasStopped() == true,
                                )
                            if (!attemptRanOutOfTime) {
                                yCoreRouteFailure(
                                    route = executedRoute,
                                    category = category,
                                    message = childState.error,
                                    reason = childState.diagnostics.reason,
                                    reported = reportedFailure,
                                )?.takeIf { it.concrete }?.let { keptRouteFailure = it }
                            }
                            if (!prematureEnd) recordLearning(childState, terminal = true)
                        }
                        if (
                            !successRecorded &&
                            childFailureKey != null &&
                            childState.diagnostics.videoOutputVerified &&
                            (childState.audioTracks.isEmpty() || childState.diagnostics.audioOutputVerified)
                        ) {
                            successRecorded = true
                            val executedFailureKey = childFailureKey.forExecutedRoute(childState.diagnostics.route)
                            failureLedger.recordSuccess(executedFailureKey)
                            // Failures an earlier build filed under the plan's label describe
                            // this same route.
                            if (executedFailureKey != childFailureKey) failureLedger.recordSuccess(childFailureKey)
                            childVerifiedRoute?.let { (verifiedItem, probe) ->
                                verifiedRouteMemory.recordVerified(verifiedItem, probe)
                            }
                        }
                        if (
                            !childState.buffering &&
                            childState.playing &&
                            childState.phase == YPlaybackPhase.Ready
                        ) {
                            scheduleNextItemPreload(childIndex())
                        }
                        if (
                            childState.phase == YPlaybackPhase.Failed &&
                            failureRecorded &&
                            !recoveryQueued
                        ) {
                            recoveryQueued = true
                            val recoveryKey =
                                RouteRecoveryKey(
                                    itemIndex = childIndex(),
                                    route = childState.diagnostics.route,
                                    category = childState.errorCategory,
                                )
                            val failedItem = queueItems[childIndex()]
                            val action =
                                YPlaybackRecoveryPolicy.decide(
                                    YPlaybackRecoveryContext(
                                        route = childState.diagnostics.route,
                                        category = childState.errorCategory,
                                        sameRouteAttempts = sameRouteRecoveryAttempts[recoveryKey] ?: 0,
                                        protectedContent = failedItem.drmConfiguration != null,
                                        softwareFallbackAttempted = childSoftwareFallbackAttempted,
                                        deterministic = attemptDeterministic,
                                        softwareFallbackAvailable =
                                            yCoreSoftwareRecoveryAvailable(
                                                compatibilityRouteAvailable = fallbackRouteFactory != null,
                                                discRouteAvailable =
                                                    failedItem.disc != null && discRouteFactory != null,
                                                protectedContent = failedItem.drmConfiguration != null,
                                                inputHdrType = childInputHdrType,
                                            ),
                                    ),
                                )
                            if (verifiedRouteSuspicion.onRecovery(action)) {
                                childVerifiedRoute?.first?.let(verifiedRouteMemory::forget)
                            }
                            // Every recovery attempt gets time to run, except after an attempt
                            // that failed only because the start ran out of time.
                            val renewProbeBudget = !attemptRanOutOfTime
                            when (action) {
                                YPlaybackRecoveryAction.RetrySameRoute -> {
                                    sameRouteRecoveryAttempts[recoveryKey] =
                                        (sameRouteRecoveryAttempts[recoveryKey] ?: 0) + 1
                                    codecResetCounts[childIndex()] = (codecResetCounts[childIndex()] ?: 0) + 1
                                    commands.trySend(
                                        Command.RecoverSameRoute(
                                            index = childIndex(),
                                            positionMs = childState.positionMs,
                                            route = childState.diagnostics.route,
                                            renewProbeBudget = renewProbeBudget,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.DisableTunnel -> {
                                    commands.trySend(
                                        Command.FallbackFromTunnel(
                                            index = childIndex(),
                                            positionMs = childState.positionMs,
                                            renewProbeBudget = renewProbeBudget,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.FallbackToEnhanced -> {
                                    commands.trySend(
                                        Command.FallbackToEnhanced(
                                            index = childIndex(),
                                            positionMs = childState.positionMs,
                                            renewProbeBudget = renewProbeBudget,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.FallbackToSoftware -> {
                                    commands.trySend(
                                        Command.FallbackToSoftware(
                                            index = childIndex(),
                                            positionMs = childState.positionMs,
                                            renewProbeBudget = renewProbeBudget,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.Stop -> Unit
                            }
                        }
                        val naturalAutoNext =
                            childState.phase == YPlaybackPhase.Ended &&
                                request.autoNext &&
                                childIndex() + 1 < queueItems.size
                        if (childState.phase == YPlaybackPhase.Failed ||
                            childState.phase == YPlaybackPhase.Ended &&
                            !naturalAutoNext
                        ) {
                            requestedPlay = false
                        }
                        if (childState.phase == YPlaybackPhase.Ready) autoNextQueued = false
                        val publishedChildState =
                            if (nativeOnly) {
                                childState.withStartFailure(attemptRanOutOfTime, keptRouteFailure)
                            } else {
                                childState
                            }
                        mutableState.value =
                            publishedChildState.copy(
                                currentIndex = childIndex(),
                                itemCount = queueItems.size,
                                playbackRequested = requestedPlay && childState.phase != YPlaybackPhase.Ended,
                                diagnostics =
                                    publishedChildState.diagnostics.copy(
                                        codecResetCount =
                                            childState.diagnostics.codecResetCount +
                                                (codecResetCounts[childIndex()] ?: 0),
                                    ),
                            )
                        // An audio route change held back during preparation applies now that
                        // the graph has reached a settled phase.
                        if (
                            childState.phase != YPlaybackPhase.Preparing &&
                            childState.phase != YPlaybackPhase.Idle &&
                            deferredAudioRouteChange.compareAndSet(true, false)
                        ) {
                            commands.trySend(Command.AudioRouteChanged)
                        }
                        handoffStartedNs?.takeIf { handoffItemId == childItemId }?.let { started ->
                            val video = childState.diagnostics.videoOutputVerified
                            val audio = childState.diagnostics.audioOutputVerified
                            if ((!handoffVideoLogged && video) || (!handoffAudioLogged && audio)) {
                                AppLog.info(
                                    category = "player.core2",
                                    event = "next_item_output_ready",
                                    message = "Output confirmed after item selection",
                                    attributes =
                                        mapOf(
                                            "elapsedMs" to ((System.nanoTime() - started) / 1_000_000L).toString(),
                                            "video" to video.toString(),
                                            "audio" to audio.toString(),
                                            "itemIndex" to currentIndex.toString(),
                                        ),
                                )
                                handoffVideoLogged = handoffVideoLogged || video
                                handoffAudioLogged = handoffAudioLogged || audio
                            }
                        }
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            !learningRecorded
                        ) {
                            recordLearning(childState, terminal = true)
                        }
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            request.autoNext &&
                            !autoNextQueued &&
                            childIndex() + 1 < queueItems.size
                        ) {
                            autoNextQueued = true
                            commands.trySend(Command.SelectItem(queueItems[childIndex() + 1].id))
                        }
                    }
                }
            next.setSecondarySubtitleOffsetMs(secondarySubtitleOffsetMs)
            next.prepare()
            if (pausedSeekPreviewRequested) {
                pausedSeekPreviewRequested = false
                next.seekTo(attachedTarget?.localPositionMs ?: pendingPositionMs)
            }
            if (requestedPlay) next.play()
        }

        /**
         * Probing the source over the network and constructing decoders can block for seconds, so it
         * leaves the one-at-a-time router dispatcher for IO. Callers stop the previous child first:
         * no collector is attached while this runs, and the monitors only post commands. A child
         * finished just as the router is cancelled is released here instead of being lost with the
         * result withContext discards.
         */
        suspend fun createChildOffRouter(
            positionMs: Long,
            budget: AndroidProbeBudget,
        ): YPlayer? {
            var created: YPlayer? = null
            try {
                withContext(Dispatchers.IO) { created = createChild(positionMs, budget) }
            } catch (cancelled: CancellationException) {
                created?.release()
                throw cancelled
            }
            return created
        }

        suspend fun rebuild(positionMs: Long) {
            val ticket = probes.begin()
            val budget = ticket.budget
            // MediaCodec instances are scarce on vendor builds. Release the failed/old graph
            // before probing and constructing its replacement so Retry cannot contend with it.
            stopChild()
            // The replacement evaluates the current output from scratch, so any route change
            // still held from the graph being replaced has already been accounted for.
            deferredAudioRouteChange.set(false)
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Preparing,
                    playing = false,
                    buffering = requestedPlay,
                    positionMs = positionMs.coerceAtLeast(0L),
                    currentIndex = currentIndex,
                    bufferedPositionMs = positionMs.coerceAtLeast(0L),
                    subtitleCues = emptyList(),
                    secondarySubtitleCues = emptyList(),
                    error = null,
                    errorCategory = null,
                    diagnostics =
                        it.diagnostics
                            .copy(
                                reason = "Evaluating YCore 2.0 route for queue item $currentIndex",
                            ).invalidateOutputEvidence(YOutputEvidenceResetReason.DecoderReconfigured),
                )
            }
            val next = createChildOffRouter(positionMs, budget)
            var transferred = false
            try {
                if (released || !probes.isCurrent(ticket)) return
                currentCoroutineContext().ensureActive()
                budget.ensureActive()
                if (next == null) {
                    publishUnavailable("No executable Core2 or compatibility route for item $currentIndex")
                } else {
                    attachChild(next)
                    transferred = true
                }
            } finally {
                if (!transferred) next?.release()
            }
        }

        try {
            for (command in commands) {
                try {
                    when (command) {
                        Command.Prepare -> {
                            keptRouteFailure = null
                            rebuild(pendingPositionMs)
                        }
                        Command.Play -> {
                            requestedPlay = true
                            val active = child
                            if (active == null) rebuild(pendingPositionMs) else active.play()
                        }
                        Command.Pause -> {
                            discardNextPreparation()
                            requestedPlay = false
                            child?.pause()
                        }
                        Command.SeekPending -> {
                            seekCommandQueued.set(false)
                            val positionMs = pendingSeekMs.getAndSet(NO_PENDING_SEEK_MS)
                            if (positionMs >= 0L) {
                                discardNextPreparation()
                                nextPreloadRetryAfterMs = 0L
                                val seekFeedbackGeneration = adaptiveFeedbackGeneration.incrementAndGet()
                                adaptiveFeedbackSink?.updatePlaybackFeedback(
                                    YAdaptivePlaybackFeedback(
                                        bufferedDurationUs = 0L,
                                        playing = false,
                                        speed = speed,
                                        generation = seekFeedbackGeneration,
                                    ),
                                )
                                pendingPositionMs = positionMs
                                if (adaptiveTarget != null) {
                                    pausedSeekPreviewRequested = !requestedPlay
                                    rebuild(positionMs)
                                } else {
                                    child?.seekTo(positionMs)
                                }
                            }
                            if (pendingSeekMs.get() >= 0L) queuePendingSeek()
                        }
                        is Command.SetSpeed -> {
                            speed = command.speed
                            val active = child
                            if (
                                active
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route == YPlaybackRoute.NativeTunnel &&
                                kotlin.math.abs(speed - 1f) > TUNNEL_SPEED_EPSILON
                            ) {
                                allowTunnel = false
                                pendingPositionMs = globalChildPosition()
                                rebuild(pendingPositionMs)
                            } else {
                                active?.setSpeed(speed)
                            }
                        }
                        is Command.SetAudioDelay -> {
                            audioDelayMs = command.delayMs
                            if (audioDelayMs != 0L &&
                                child
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route == YPlaybackRoute.NativeTunnel
                            ) {
                                allowTunnel = false
                                pendingPositionMs = globalChildPosition()
                                rebuild(pendingPositionMs)
                            } else {
                                child?.setAudioDelayMs(audioDelayMs)
                            }
                        }
                        is Command.AdaptiveTransition -> {
                            if (child === command.fromChild) {
                                pendingAdaptiveTarget = command.target
                                pendingPositionMs = command.globalPositionMs
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.SecondarySubtitleOffset -> {
                            secondarySubtitleOffsetMs = command.offsetMs
                            child?.setSecondarySubtitleOffsetMs(command.offsetMs)
                        }
                        is Command.SelectSecondarySubtitle -> {
                            child?.selectSecondarySubtitleTrack(command.id)
                        }
                        is Command.SelectTrack -> {
                            val active = child
                            val state = active?.state?.value ?: continue
                            val skipReason = state.trackSelectionSkipReason(command.type, command.id)
                            if (skipReason != null) {
                                if (loggedTrackSkips.add("$currentIndex:${command.type}:$skipReason")) {
                                    AppLog.info(
                                        category = "player.core2",
                                        event = "track_selection_skipped",
                                        message = "Track selection does not require a playback change",
                                        attributes = mapOf("type" to command.type.name, "reason" to skipReason),
                                    )
                                }
                                continue
                            }
                            val tracks =
                                if (command.type ==
                                    YTrackType.Audio
                                ) {
                                    state.audioTracks
                                } else {
                                    state.subtitleTracks
                                }
                            val preference = tracks.firstOrNull { it.id == command.id }?.preferenceIn(tracks)
                            val currentItem = queueItems[currentIndex]
                            val previousSelection =
                                runtimeTrackSelections[currentItem.id]
                                    ?: currentItem.initialTrackSelection ?: YInitialTrackSelection()
                            runtimeTrackSelections[currentItem.id] =
                                if (command.type == YTrackType.Audio) {
                                    previousSelection.copy(audio = preference)
                                } else {
                                    previousSelection.copy(
                                        subtitle = preference,
                                        subtitlesDisabled =
                                            command.id == "off",
                                    )
                                }
                            if (active
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route == YPlaybackRoute.NativeTunnel
                            ) {
                                allowTunnel = false
                                pendingPositionMs = globalChildPosition()
                                AppLog.info(
                                    category = "player.core2",
                                    event = "track_selection_route_rebuild",
                                    message = "Changing an active Tunnel track requires a new playback graph",
                                    attributes = mapOf("type" to command.type.name, "reason" to "tunnel_track_change"),
                                )
                                rebuild(pendingPositionMs)
                                // The new demux resolves the stable preference itself. Platform and
                                // FFmpeg stream indexes need not agree, so never forward the old id.
                                continue
                            }
                            child?.selectTrack(command.type, command.id)
                        }
                        is Command.SetVideoOutput -> {
                            output = command.output
                            val active = child
                            if (active != null) {
                                // The attach may already have handed this very output over.
                                if (output != childVideoOutput) {
                                    active.setVideoOutput(output)
                                    childVideoOutput = output
                                }
                            } else if (
                                output != null &&
                                mutableState.value.phase != YPlaybackPhase.Idle &&
                                mutableState.value.phase != YPlaybackPhase.Failed
                            ) {
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.SelectItem -> {
                            val selectedIndex = queueItems.indexOfFirst { it.id == command.itemId }
                            if (selectedIndex < 0) continue
                            nextItemPreloadJob?.cancel()
                            nextItemPreloadJob = null
                            if (preloadedNextRoute?.index != selectedIndex) {
                                preloadedNextRoute?.sources?.close()
                                preloadedNextRoute = null
                            }
                            val preparedDecision = preloadedNextRoute?.decision
                            if (selectedIndex == currentIndex + 1 &&
                                preparedDecision?.nativeDirectExecutable == true &&
                                preparedDecision.probe.playbackRequest.video.hdrType == YHdrType.Sdr &&
                                preparedDecision.plan.audioPath == YAudioOutputPath.DecodePcm
                            ) {
                                (child as? AndroidNativeDirectYPlayer)?.prepareVideoHandoff()
                            } else {
                                videoHandoff.close()
                            }
                            handoffItemId = command.itemId
                            handoffStartedNs = System.nanoTime()
                            handoffVideoLogged = false
                            handoffAudioLogged = false
                            pendingPositionMs = 0L
                            currentIndex = selectedIndex
                            sameRouteRecoveryAttempts.keys.removeAll { it.itemIndex == currentIndex }
                            codecResetCounts.remove(currentIndex)
                            allowTunnel = true
                            forceEnhancedFallback = false
                            forceSoftwareFallback = false
                            keptRouteFailure = null
                            rebuild(0L)
                        }
                        Command.QueueUpdated -> {
                            discardNextPreparation()
                            nextPreloadRetryAfterMs = 0L
                            sameRouteRecoveryAttempts.clear()
                            codecResetCounts.clear()
                            mutableState.updateState {
                                it.copy(
                                    currentIndex = currentIndex,
                                    itemCount = queueItems.size,
                                )
                            }
                            if (child?.state?.value?.phase ==
                                YPlaybackPhase.Ready
                            ) {
                                scheduleNextItemPreload(currentIndex)
                            }
                        }
                        Command.QueueExtended -> {
                            mutableState.updateState { it.copy(itemCount = queueItems.size) }
                            if (child?.state?.value?.phase == YPlaybackPhase.Ready) {
                                scheduleNextItemPreload(currentIndex)
                            }
                        }
                        Command.PreparationBoundaryChanged -> {
                            discardNextPreparation()
                            nextPreloadRetryAfterMs = 0L
                            scheduleNextItemPreload(currentIndex)
                        }
                        is Command.NextItemPreloaded -> {
                            val item = queueItems.getOrNull(command.route.index)
                            if (command.revision != nextPreparationRevision.get() ||
                                child !== command.fromChild ||
                                item == null ||
                                command.route.index != currentIndex + 1 ||
                                !command.route.sourceItem.matchesPreparedSource(item)
                            ) {
                                command.route.sources.close()
                                continue
                            }
                            nextItemPreloadJob = null
                            preloadedNextRoute?.sources?.close()
                            preloadedNextRoute = command.route
                            AppLog.info(
                                category = "player.core2",
                                event = "next_item_preloaded",
                                message = "YCore next-item route and expiring media sources prepared",
                                attributes = mapOf("itemIndex" to command.route.index.toString()),
                            )
                        }
                        Command.AudioRouteChanged -> {
                            audioRouteChangeQueued.set(false)
                            val phase = mutableState.value.phase
                            when {
                                phase == YPlaybackPhase.Ended || phase == YPlaybackPhase.Failed -> Unit
                                child == null && phase == YPlaybackPhase.Idle -> Unit
                                // A route change during startup is nearly always the system
                                // settling the output around the AudioTrack this graph is in the
                                // middle of creating. Rebuilding then discards a probe, a decoder
                                // and an open byte range only to arrive at the same route - and
                                // does it while the first frame is still pending. Hold it, and
                                // apply it once the graph is actually up.
                                phase == YPlaybackPhase.Preparing ->
                                    deferredAudioRouteChange.set(true)
                                else -> {
                                    pendingPositionMs =
                                        if (child != null) globalChildPosition() else mutableState.value.positionMs
                                    forceEnhancedFallback = false
                                    forceSoftwareFallback = false
                                    rebuild(pendingPositionMs)
                                }
                            }
                        }
                        Command.ThermalPressure -> {
                            if (mutableState.value.phase in setOf(YPlaybackPhase.Ended, YPlaybackPhase.Failed)) continue
                            discardNextPreparation()
                            val activeRoute =
                                child
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route
                            if (
                                activeRoute == YPlaybackRoute.GpuEnhanced ||
                                activeRoute == YPlaybackRoute.SoftwareFallback
                            ) {
                                pendingPositionMs =
                                    if (child != null) globalChildPosition() else mutableState.value.positionMs
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.FallbackFromTunnel -> {
                            if (
                                command.index == currentIndex &&
                                child
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route == YPlaybackRoute.NativeTunnel
                            ) {
                                allowTunnel = false
                                pendingPositionMs = command.positionMs
                                if (command.renewProbeBudget) ensureRecoveryProbeBudget()
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.RecoverSameRoute -> {
                            val active = child
                            val activeState = active?.state?.value
                            if (
                                command.index == currentIndex &&
                                activeState != null &&
                                activeState.phase in
                                setOf(YPlaybackPhase.Failed, YPlaybackPhase.Ended) &&
                                activeState.diagnostics.route == command.route
                            ) {
                                pendingPositionMs = command.positionMs
                                if (command.renewProbeBudget) ensureRecoveryProbeBudget()
                                if (canRetryCore2RouteInPlace(command.route, active is AndroidNativeEnhancedYPlayer)) {
                                    // The child owns a serialized codec command queue. Reusing it
                                    // guarantees releaseMedia() finishes before the same decoder is
                                    // configured again; rebuilding here allowed the replacement
                                    // child to race the outgoing MediaCodec release on OEM devices.
                                    if (active is AndroidNativeEnhancedYPlayer) {
                                        active.retryWithProbeBudget(activeProbeBudget)
                                    } else {
                                        checkNotNull(active).retry()
                                    }
                                    // Ended/failed children may have cleared their own play intent.
                                    // Restore the router's latest user intent after serialized prepare.
                                    if (requestedPlay) active.play() else active.pause()
                                } else {
                                    rebuild(pendingPositionMs)
                                }
                            }
                        }
                        is Command.FallbackToEnhanced -> {
                            val activeRoute =
                                child
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route
                            if (
                                command.index == currentIndex &&
                                activeRoute != null &&
                                activeRoute !in
                                setOf(
                                    YPlaybackRoute.NativeEnhanced,
                                    YPlaybackRoute.GpuEnhanced,
                                    YPlaybackRoute.SoftwareFallback,
                                )
                            ) {
                                allowTunnel = false
                                forceEnhancedFallback = true
                                forceSoftwareFallback = false
                                pendingPositionMs = command.positionMs
                                if (command.renewProbeBudget) ensureRecoveryProbeBudget()
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.FallbackToSoftware -> {
                            if (
                                command.index == currentIndex &&
                                child
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route !=
                                YPlaybackRoute.SoftwareFallback
                            ) {
                                allowTunnel = false
                                forceEnhancedFallback = false
                                forceSoftwareFallback = true
                                pendingPositionMs = command.positionMs
                                if (command.renewProbeBudget) ensureRecoveryProbeBudget()
                                rebuild(pendingPositionMs)
                            }
                        }
                        Command.Retry -> {
                            sameRouteRecoveryAttempts.keys.removeAll { it.itemIndex == currentIndex }
                            codecResetCounts.remove(currentIndex)
                            allowTunnel = true
                            forceEnhancedFallback = false
                            forceSoftwareFallback = false
                            bypassLearnedRouteMemoryOnce = true
                            pendingFailureKey = null
                            keptRouteFailure = null
                            pendingPositionMs =
                                mutableState.value
                                    .takeIf { it.currentIndex == currentIndex }
                                    ?.positionMs
                                    ?.coerceAtLeast(0L)
                                    ?: pendingPositionMs
                            val active = child
                            val activeState = active?.state?.value
                            if (
                                active != null &&
                                activeState != null &&
                                shouldRetryActiveNativeChildInPlace(
                                    nativeOnly = nativeOnly,
                                    phase = activeState.phase,
                                    route = activeState.diagnostics.route,
                                    enhancedChild = active is AndroidNativeEnhancedYPlayer,
                                )
                            ) {
                                // Runtime silent-output recovery is not a route change. Keep the
                                // active child and let its worker release, reopen and configure in
                                // strict order instead of constructing a competing codec instance.
                                if (active is AndroidNativeEnhancedYPlayer) {
                                    active.retryWithProbeBudget(activeProbeBudget)
                                } else {
                                    active.retry()
                                }
                            } else {
                                rebuild(pendingPositionMs)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    if (released) break
                    if (failure is AndroidProbeAbortedException && failure.reason == "superseded") continue
                    val probeTimedOut = failure is AndroidProbeAbortedException
                    if (failure is AndroidProbeAbortedException) {
                        AppLog.warning(
                            category = "player.core2",
                            event = "startup_probe_aborted",
                            message = "YCore stopped source preparation within its shared deadline",
                            attributes =
                                mapOf(
                                    "reason" to failure.reason,
                                    "generation" to probes.generation().toString(),
                                    "keptFailureCategory" to keptRouteFailure?.category?.name.orEmpty(),
                                ),
                        )
                    }
                    // The timeout text is only the fallback: a route that failed concretely
                    // before this start ran out of time is what the user is told about.
                    publishUnavailable(
                        reason = core2RouterFailureReason(failure),
                        sourceFailure = failure.mediaSourceFailure(),
                        probeTimedOut = probeTimedOut,
                    )
                }
            }
        } finally {
            probes.invalidate("released")
            discardNextPreparation()
            // A bounded, non-suspending drain, deliberately not `while (isActive)`: this runs in the
            // worker's `finally`, where the scope is already cancelled, and skipping it would leak
            // the sources of every preloaded route still sitting in the channel.
            var pending = commands.tryReceive().getOrNull()
            while (pending != null) {
                if (pending is Command.NextItemPreloaded) pending.route.sources.close()
                pending = commands.tryReceive().getOrNull()
            }
            try {
                stopChild(waitForRelease = false)
            } finally {
                routeEvaluator.closePreparedExtractor()
                routeEvaluator.closePreparedEnhancedDemux()
                videoHandoff.close()
            }
        }
    }

    private sealed interface Command {
        data class SetAudioDelay(
            val delayMs: Long,
        ) : Command

        data class AdaptiveTransition(
            val fromChild: YPlayer,
            val globalPositionMs: Long,
            val target: YAdaptivePlaybackTarget?,
        ) : Command

        data object Prepare : Command

        data object Play : Command

        data object Pause : Command

        data object Retry : Command

        data object QueueExtended : Command

        data object QueueUpdated : Command

        data object PreparationBoundaryChanged : Command

        data object AudioRouteChanged : Command

        data object ThermalPressure : Command

        data class NextItemPreloaded(
            val fromChild: YPlayer,
            val route: PreloadedNextRoute,
            val revision: Long,
        ) : Command

        data object SeekPending : Command

        data class SetSpeed(
            val speed: Float,
        ) : Command

        data class SecondarySubtitleOffset(
            val offsetMs: Long,
        ) : Command

        data class SelectSecondarySubtitle(
            val id: String,
        ) : Command

        data class SelectTrack(
            val type: YTrackType,
            val id: String,
        ) : Command

        data class SetVideoOutput(
            val output: YVideoOutput?,
        ) : Command

        data class SelectItem(
            val itemId: String,
        ) : Command

        data class FallbackFromTunnel(
            val index: Int,
            val positionMs: Long,
            val renewProbeBudget: Boolean = false,
        ) : Command

        /**
         * [renewProbeBudget] is set when the recovery follows a failure of the route itself rather
         * than the start running out of time (see ensureRecoveryProbeBudget); the transport
         * recovery that reopens a stalled read keeps the start's deadline.
         */
        data class RecoverSameRoute(
            val index: Int,
            val positionMs: Long,
            val route: YPlaybackRoute,
            val renewProbeBudget: Boolean = false,
        ) : Command

        data class FallbackToSoftware(
            val index: Int,
            val positionMs: Long,
            val renewProbeBudget: Boolean = false,
        ) : Command

        data class FallbackToEnhanced(
            val index: Int,
            val positionMs: Long,
            val renewProbeBudget: Boolean = false,
        ) : Command
    }

    private data class RouteRecoveryKey(
        val itemIndex: Int,
        val route: YPlaybackRoute,
        val category: YPlaybackFailureCategory?,
    )

    private data class PreloadedNextRoute(
        val index: Int,
        val sourceItem: YMediaItem,
        val preferTunnel: Boolean,
        val allowAudioPassthrough: Boolean,
        val forcePowerSaver: Boolean,
        val decision: YCore2RouteDecision,
        val sources: AndroidNextItemSources,
        val preparedAtNs: Long = System.nanoTime(),
    ) {
        fun matches(
            index: Int,
            item: YMediaItem,
            preferTunnel: Boolean,
            allowAudioPassthrough: Boolean,
            forcePowerSaver: Boolean,
        ): Boolean =
            this.index == index &&
                System.nanoTime() - preparedAtNs < 30_000_000_000L &&
                sourceItem.matchesPreparedSource(item) &&
                sourceItem.sourceHints == item.sourceHints &&
                sourceItem.allExternalSubtitles == item.allExternalSubtitles &&
                this.preferTunnel == preferTunnel &&
                this.allowAudioPassthrough == allowAudioPassthrough &&
                this.forcePowerSaver == forcePowerSaver
    }
}

internal class AndroidCore2PlayerFactory(
    private val context: Context,
) : YPlayerFactory {
    override fun create(request: YPlayerOpenRequest): YPlayer {
        val compatibilityFactory = AndroidMpvCore2FallbackFactory(context)
        val discFactory =
            AndroidYCoreDiscRouteFactory(
                context = context,
                allowAudioPassthrough = true,
                frameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
                fallback = compatibilityFactory,
            )
        return AndroidAdaptiveCore2YPlayer(
            context = context,
            request = request,
            fallbackRouteFactory = compatibilityFactory,
            discRouteFactory = discFactory,
        )
    }
}

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    update(transform)
}

private const val TUNNEL_SPEED_EPSILON = 0.001f
private const val NO_PENDING_SEEK_MS = -1L
private const val THERMAL_POLL_INTERVAL_MS = 30_000L
private const val SEVERE_THERMAL_STATUS = 3
private const val RELEASE_JOIN_TIMEOUT_MS = 5_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

/**
 * What a recovery attempt of a start that has not rendered is guaranteed, however little of the
 * start's own deadline is left. The failed route has just read the source, and probe results are
 * cached for the session, so this covers the new route's open rather than a second full probe.
 */
private const val RECOVERY_PROBE_ALLOWANCE_MS = 20_000L

/** Throwable names survive release obfuscation (see diagnosticTypeName); the message never enters. */
internal fun core2RouterFailureReason(failure: Throwable): String =
    "Core2 router failed at ${failure.diagnosticTypeName()}"

/** True when a recovery attempt with [remainingStartMs] of the start's deadline left needs its own. */
internal fun yCoreRecoveryNeedsFreshProbeBudget(
    remainingStartMs: Long,
    allowanceMs: Long = RECOVERY_PROBE_ALLOWANCE_MS,
): Boolean = remainingStartMs < allowanceMs

/**
 * Whether a failed attempt ended because its start ran out of time rather than on the media.
 *
 * A reported failure is decided by its root cause. Without one, only an unclassified failure after
 * the attempt's deadline stopped counts: a typed failure says what went wrong, deadline or not.
 */
internal fun yCoreAttemptRanOutOfTime(
    reported: Throwable?,
    category: YPlaybackFailureCategory?,
    attemptDeadlineStopped: Boolean,
): Boolean =
    if (reported != null) {
        reported.diagnosticRootCause() is AndroidProbeAbortedException
    } else {
        attemptDeadlineStopped && (category == null || category == YPlaybackFailureCategory.Unknown)
    }

/** Failure categories that say something about this media or device, unlike transport or deadline failures. */
private val CONCRETE_ROUTE_FAILURES =
    setOf(
        YPlaybackFailureCategory.Authorization,
        YPlaybackFailureCategory.Drm,
        YPlaybackFailureCategory.Container,
        YPlaybackFailureCategory.Decoder,
        YPlaybackFailureCategory.Renderer,
        YPlaybackFailureCategory.AudioSink,
    )

/**
 * One route's failure as the router keeps it once the rebuild after it cleared the visible error.
 *
 * [message] is the localized text the route published, never a throwable message; [reason] names
 * the route, the stage and the typed safe detail, which by contract carries no URL or credential.
 */
internal data class YCoreRouteFailure(
    val route: YPlaybackRoute,
    val category: YPlaybackFailureCategory,
    val message: String,
    val reason: String,
) {
    val concrete: Boolean get() = category in CONCRETE_ROUTE_FAILURES
}

/** Builds the kept failure from a child's Failed state and, when the child reports it, the typed failure. */
internal fun yCoreRouteFailure(
    route: YPlaybackRoute,
    category: YPlaybackFailureCategory?,
    message: String?,
    reason: String?,
    reported: Throwable?,
): YCoreRouteFailure? {
    val typed = reported as? YPlaybackException
    val resolvedCategory = typed?.category ?: category ?: return null
    val resolvedMessage =
        when {
            typed.isHiddenServerAudioTrackFailure() -> YCORE_HIDDEN_AUDIO_TRACK_MESSAGE
            !message.isNullOrBlank() -> message
            else -> yCoreEnhancedFailureMessage(typed)
        }
    val resolvedReason =
        typed?.let { failure ->
            buildString {
                append(route.name)
                append(" failed at ")
                append(failure.stage.name)
                failure.safeDetail?.takeIf(String::isNotBlank)?.let { detail ->
                    append(": ")
                    append(detail)
                }
            }
        } ?: reason?.takeIf(String::isNotBlank) ?: "${route.name} failed"
    return YCoreRouteFailure(
        route = route,
        category = resolvedCategory,
        message = resolvedMessage,
        reason = resolvedReason,
    )
}

/**
 * The state a start that stops on a failed attempt publishes.
 *
 * When the attempt only ran out of its startup deadline after an earlier route of the same start
 * failed concretely, that earlier failure is the reason the media did not play: the deadline is
 * its consequence, and a "probe timed out" message sent users to check a network that was fine.
 */
internal fun YPlayerState.withStartFailure(
    attemptRanOutOfTime: Boolean,
    kept: YCoreRouteFailure?,
): YPlayerState {
    if (phase != YPlaybackPhase.Failed || !attemptRanOutOfTime || kept == null || !kept.concrete) return this
    return copy(
        error = kept.message,
        errorCategory = kept.category,
        diagnostics =
            diagnostics.copy(
                reason = "${kept.reason}; ${diagnostics.route.name} then ran out of its startup deadline",
            ),
    )
}

internal data class YCoreUnavailableError(
    val message: String,
    val category: YPlaybackFailureCategory,
    val reason: String,
)

/**
 * What a start publishes when the router itself cannot produce another route.
 *
 * A typed source failure the router hit is current evidence and wins. On a native-only start the
 * last concrete route failure comes next, then the probe timeout, which is only the fallback when
 * nothing more concrete is known. With a compatibility engine the failure stays Unknown on
 * purpose: that is what hands the item to the product-level Legacy fallback.
 */
internal fun yCoreUnavailableError(
    routerReason: String,
    sourceFailure: YPlaybackException?,
    keptFailure: YCoreRouteFailure?,
    protectedContent: Boolean,
    nativeOnly: Boolean,
    probeTimedOut: Boolean,
    currentError: String?,
    currentCategory: YPlaybackFailureCategory?,
): YCoreUnavailableError {
    val kept = keptFailure?.takeIf { nativeOnly && it.concrete }
    return when {
        sourceFailure != null ->
            YCoreUnavailableError(yCoreEnhancedFailureMessage(sourceFailure), sourceFailure.category, routerReason)
        kept != null ->
            YCoreUnavailableError(kept.message, kept.category, "${kept.reason}; recovery ended: $routerReason")
        protectedContent ->
            YCoreUnavailableError(
                message = "YCore 2.0 无法打开当前受保护片源，设备未提供可执行的安全解码路径",
                category =
                    when {
                        !nativeOnly -> YPlaybackFailureCategory.Unknown
                        probeTimedOut -> YPlaybackFailureCategory.Network
                        else -> currentCategory ?: YPlaybackFailureCategory.Unknown
                    },
                reason = routerReason,
            )
        nativeOnly && probeTimedOut ->
            YCoreUnavailableError(
                message = "YCore 2.0 片源起播探测超时，请检查网络或刷新片源后重试",
                category = YPlaybackFailureCategory.Network,
                reason = routerReason,
            )
        nativeOnly ->
            YCoreUnavailableError(
                message = currentError ?: "YCore 2.0 纯内核路径无法打开当前片源",
                category = currentCategory ?: YPlaybackFailureCategory.Unknown,
                reason = routerReason,
            )
        else ->
            YCoreUnavailableError(
                message = "YCore 2.0 与兼容内核均无法打开当前片源",
                category = YPlaybackFailureCategory.Unknown,
                reason = routerReason,
            )
    }
}

/**
 * Whether FallbackToSoftware has a route to build for this input (see createInternalSoftwareRoute).
 * Without a compatibility engine, Dolby Vision has none: YCore never decodes it in software.
 */
internal fun yCoreSoftwareRecoveryAvailable(
    compatibilityRouteAvailable: Boolean,
    discRouteAvailable: Boolean,
    protectedContent: Boolean,
    inputHdrType: YHdrType,
): Boolean =
    compatibilityRouteAvailable ||
        discRouteAvailable ||
        (!protectedContent && yCoreInternalSoftwareRecoveryPlan(inputHdrType) != null)

private fun AndroidProbeBudget.remainingMsOrZero(): Long =
    try {
        remainingMs()
    } catch (_: AndroidProbeAbortedException) {
        0L
    }

/** Stopped for any reason, deadline included; a budget completed by rendered output never is. */
private fun AndroidProbeBudget.hasStopped(): Boolean =
    try {
        ensureActive()
        false
    } catch (_: AndroidProbeAbortedException) {
        true
    }

/** A router's own view of Default: its coroutines run one at a time, so its plain state has one owner. */
internal fun androidCore2RouterDispatcher(): CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

internal fun shouldRetryActiveNativeChildInPlace(
    nativeOnly: Boolean,
    phase: YPlaybackPhase,
    route: YPlaybackRoute,
    enhancedChild: Boolean = false,
): Boolean =
    nativeOnly &&
        phase != YPlaybackPhase.Failed &&
        canRetryCore2RouteInPlace(route, enhancedChild)

internal fun canRetryCore2RouteInPlace(
    route: YPlaybackRoute,
    enhancedChild: Boolean = false,
): Boolean =
    (route == YPlaybackRoute.SoftwareFallback && enhancedChild) ||
        route in
        setOf(
            YPlaybackRoute.NativeTunnel,
            YPlaybackRoute.NativeDirect,
            YPlaybackRoute.NativeEnhanced,
            YPlaybackRoute.GpuEnhanced,
        )

/** Failure categories that say nothing about whether the remembered media facts still hold. */
private val VERIFIED_ROUTE_NEUTRAL_FAILURES =
    setOf(
        YPlaybackFailureCategory.Network,
        YPlaybackFailureCategory.Authorization,
        YPlaybackFailureCategory.Drm,
    )

/**
 * Decides when a local failure makes the router forget a route verified for this media.
 *
 * A local failure on the remembered route means the remembered facts may no longer describe this
 * media on this device; a transport, account or DRM failure says nothing about them. The route is
 * forgotten once recovery leaves it. A startup failure that the same route then recovers from and
 * renders (1.0.83: a NativeDirect IllegalStateException that played 2 s later) proves the facts,
 * and forgetting them made the next start of the title pay every probe again.
 */
internal class YCoreVerifiedRouteSuspicion {
    var suspect: Boolean = false
        private set

    fun onFailure(category: YPlaybackFailureCategory?) {
        if (category != null && category !in VERIFIED_ROUTE_NEUTRAL_FAILURES) suspect = true
    }

    fun onVideoOutput() {
        suspect = false
    }

    /** True when the route must be forgotten now, because recovery is leaving it or stopping. */
    fun onRecovery(action: YPlaybackRecoveryAction): Boolean {
        if (!suspect || action == YPlaybackRecoveryAction.RetrySameRoute) return false
        suspect = false
        return true
    }
}

private const val MIN_LEARNING_PLAYBACK_MS = 30_000L
private const val MAX_CONSECUTIVE_NETWORK_RECOVERY_ATTEMPTS = 2
private const val MICROSECONDS_PER_MILLISECOND = 1_000L

private fun YCore2FailureKey.toLearningKey(): YPlaybackLearningKey =
    YPlaybackLearningKey(
        route = route,
        container = container,
        videoCodec = videoCodec,
        hdrType = hdrType,
        decoderName = decoderName,
    )

internal fun YPlaybackFailureCategory?.allowsCore2LocalSoftwareFallback(): Boolean =
    this == null ||
        this == YPlaybackFailureCategory.Container ||
        this == YPlaybackFailureCategory.Decoder ||
        this == YPlaybackFailureCategory.Renderer ||
        this == YPlaybackFailureCategory.AudioSink ||
        this == YPlaybackFailureCategory.Unknown

internal fun YPlaybackPlan.toSoftwareFallbackPlan(reason: String): YPlaybackPlan {
    val needsOwnedToneMap =
        !usesHdrFallback && inputHdrType in setOf(YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.Hlg)
    return copy(
        route = YPlaybackRoute.SoftwareFallback,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Software,
        renderPath = YRenderPath.Gpu,
        outputHdrType = if (needsOwnedToneMap) YHdrType.Sdr else outputHdrType,
        decoderName = null,
        audioPath = if (audioPath == YAudioOutputPath.None) YAudioOutputPath.None else YAudioOutputPath.DecodePcm,
        softwareAudioDecode = softwareAudioDecode || !nativeAudio,
        softwareVideoToneMap = needsOwnedToneMap,
        reason = reason,
    )
}

internal fun shouldBypassLearnedYCoreRouteMemory(
    manualRetry: Boolean,
    compatibilityRouteAvailable: Boolean,
): Boolean = manualRetry || !compatibilityRouteAvailable

internal fun yCoreInternalEnhancedRecoveryPlan(
    inputHdrType: YHdrType,
    decoderName: String? = null,
    audioPath: YAudioOutputPath = YAudioOutputPath.DecodePcm,
): YPlaybackPlan =
    YPlaybackPlan(
        route = YPlaybackRoute.NativeEnhanced,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Hardware,
        renderPath = YRenderPath.SurfaceDirect,
        outputHdrType = inputHdrType,
        inputHdrType = inputHdrType,
        decoderName = decoderName,
        nativeAudio = true,
        audioPath = audioPath,
        reason = "YCore internal enhanced demux and MediaCodec recovery",
    )

internal fun yCoreInternalSoftwareRecoveryPlan(inputHdrType: YHdrType): YPlaybackPlan? {
    if (inputHdrType == YHdrType.DolbyVision) return null
    return YPlaybackPlan(
        route = YPlaybackRoute.SoftwareFallback,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Software,
        renderPath = YRenderPath.Gpu,
        outputHdrType = YHdrType.Sdr,
        inputHdrType = inputHdrType,
        nativeAudio = false,
        audioPath = YAudioOutputPath.DecodePcm,
        softwareAudioDecode = true,
        softwareVideoToneMap = inputHdrType != YHdrType.Sdr,
        usesHdrFallback = false,
        reason = "YCore internal FFmpeg software decode recovery",
    )
}

private fun yCoreInconclusiveSourceCompatibilityPlan(item: YMediaItem): YPlaybackPlan {
    val inputHdrType = item.hintedHdrType()
    return YPlaybackPlan(
        route = YPlaybackRoute.GpuEnhanced,
        demuxPath = YDemuxPath.Enhanced,
        decodePath = YDecodePath.Hardware,
        renderPath = YRenderPath.Gpu,
        outputHdrType = inputHdrType,
        inputHdrType = inputHdrType,
        nativeAudio = true,
        audioPath = YAudioOutputPath.DecodePcm,
        reason = "Compatibility executor after inconclusive YCore source probe",
    )
}

private fun YMediaItem.hintedHdrType(): YHdrType {
    val range =
        sourceHints
            ?.dynamicRange
            .orEmpty()
            .trim()
            .lowercase()
    return when {
        sourceHints?.dolbyVision == true || "dolby" in range || range.startsWith("dv") ->
            YHdrType.DolbyVision
        "hdr10+" in range || "hdr10plus" in range -> YHdrType.Hdr10Plus
        "hlg" in range -> YHdrType.Hlg
        "hdr" in range -> YHdrType.Hdr10
        else -> YHdrType.Sdr
    }
}

private fun yCoreSoftwarePlanExecutable(plan: YPlaybackPlan): Boolean {
    if (!yCoreSoftwarePlanPassesDolbyGuard(plan)) return false
    return runCatching {
        AndroidFfmpegDemuxer().let { demuxer ->
            demuxer.available && demuxer.softwareDecodeAvailable
        }
    }.getOrDefault(false)
}

/**
 * The Dolby Vision and HDR-fallback guard protects FFmpeg video decode only, which would decode the
 * Dolby base layer into a wrong picture. A plan whose video stays on a platform decoder is labelled
 * SoftwareFallback only because its audio is decoded in software, and the guard refused exactly
 * that in 1.0.83 (internal_route_unavailable dolbyguard=true for a hardware-decoded DV P5 plan).
 */
internal fun yCoreSoftwarePlanPassesDolbyGuard(plan: YPlaybackPlan): Boolean =
    plan.decodePath != YDecodePath.Software ||
        !(
            plan.usesHdrFallback ||
                plan.inputHdrType == YHdrType.DolbyVision ||
                plan.outputHdrType == YHdrType.DolbyVision
        )

internal fun YPlaybackPlan.withNativeGpuFallbackTruth(probe: YNativeGpuRuntimeProbe): YPlaybackPlan {
    if (route != YPlaybackRoute.GpuEnhanced) return this
    val reason =
        probe.firstMissingRequirement()?.let { requirement ->
            "$reason; native Vulkan blocked at $requirement"
        } ?: "$reason; native Vulkan presentation executor is not installed"
    return copy(reason = reason)
}
