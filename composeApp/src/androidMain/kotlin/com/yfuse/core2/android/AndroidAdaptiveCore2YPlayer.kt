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
import com.yfuse.core.logging.playbackDiagnosticTrace
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YOutputEvidenceResetReason
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
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.learning.YLearnedRouteAdvice
import com.yfuse.core2.learning.YPlaybackLearningEngine
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.learning.YPlaybackObservation
import com.yfuse.core2.legacy.AndroidMpvCore2FallbackFactory
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.quirk.YCore2FailureLedger
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
) : YPlayer {
    private val nativeOnly = fallbackRouteFactory == null
    private val queueLock = Any()

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
    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker = scope.launch { runLoop() }
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val batteryManager = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val thermalMonitor =
        scope.launch {
            var severe = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            while (true) {
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

    override fun prepare() = send(Command.Prepare)

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released) return false
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

    override fun selectItem(index: Int) {
        if (released || index !in queueItems.indices) return
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

    override fun retry() = send(Command.Retry)

    override fun release() {
        if (released) return
        released = true
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
        var forceEnhancedFallback = false
        var forceSoftwareFallback = false
        var bypassLearnedRouteMemoryOnce = false
        var pendingFailureKey: YCore2FailureKey? = null

        /** The media and probe behind the child being started, recorded once the child renders. */
        var pendingVerifiedRoute: Pair<YMediaItem, YCore2ProbeResult.Success>? = null
        var finalizeChildLearning: (() -> Unit)? = null
        var nextItemPreloadJob: Job? = null
        var preloadedNextRoute: PreloadedNextRoute? = null
        val adaptiveFeedbackGeneration = AtomicLong(0L)
        val sameRouteRecoveryAttempts = mutableMapOf<RouteRecoveryKey, Int>()
        val codecResetCounts = mutableMapOf<Int, Int>()

        fun stopChild() {
            nextItemPreloadJob?.cancel()
            nextItemPreloadJob = null
            finalizeChildLearning?.invoke()
            finalizeChildLearning = null
            childCollector?.cancel()
            childCollector = null
            child?.release()
            child = null
            secondarySubtitleSupported = false
            activeChild = null
        }

        fun publishUnavailable(reason: String) {
            stopChild()
            val item = queueItems[currentIndex]
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = requestedPlay,
                    buffering = false,
                    error =
                        if (item.drmConfiguration != null) {
                            "YCore 2.0 无法打开当前受保护片源，" +
                                "设备未提供可执行的安全解码路径"
                        } else if (nativeOnly) {
                            it.error ?: "YCore 2.0 纯内核路径无法打开当前片源"
                        } else {
                            "YCore 2.0 与兼容内核均无法打开当前片源"
                        },
                    errorCategory =
                        if (nativeOnly) {
                            it.errorCategory ?: YPlaybackFailureCategory.Unknown
                        } else {
                            YPlaybackFailureCategory.Unknown
                        },
                    diagnostics =
                        it.diagnostics.copy(
                            route =
                                if (nativeOnly) {
                                    it.diagnostics.route
                                } else {
                                    YPlaybackRoute.Legacy
                                },
                            reason = reason,
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

        fun scheduleNextItemPreload(fromIndex: Int) {
            if (!request.autoNext || fromIndex != currentIndex) return
            val preloadChild = child ?: return
            val nextIndex = fromIndex + 1
            val item = queueItems.getOrNull(nextIndex) ?: return
            if (item.disc != null || item.drmConfiguration != null) return
            val forcePowerSaver = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            val preferTunnel =
                item.allExternalSubtitles.isEmpty() &&
                    kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            if (
                preloadedNextRoute?.matches(
                    index = nextIndex,
                    item = item,
                    preferTunnel = preferTunnel,
                    allowAudioPassthrough = allowAudioPassthrough,
                    forcePowerSaver = forcePowerSaver,
                ) == true ||
                nextItemPreloadJob?.isActive == true
            ) {
                return
            }
            nextItemPreloadJob =
                scope.launch(Dispatchers.IO) {
                    // Probing the next episode right after the first frame competes with the
                    // current item's read-ahead for the same link; wait for steady playback first.
                    val ready =
                        awaitCore2NextItemPreloadWindow {
                            activeChild?.takeIf { it === preloadChild }?.state?.value
                        }
                    if (!ready) return@launch
                    runCatching {
                        routeEvaluator.evaluate(
                            item = item,
                            preferTunnel = preferTunnel,
                            allowAudioPassthrough = allowAudioPassthrough,
                            forcePowerSaver = forcePowerSaver,
                            prepareSourceForPlayback = false,
                        )
                    }.onSuccess { decision ->
                        currentCoroutineContext().ensureActive()
                        if (activeChild !== preloadChild) return@launch
                        if (decision != null) {
                            commands.trySend(
                                Command.NextItemPreloaded(
                                    fromChild = preloadChild,
                                    route =
                                        PreloadedNextRoute(
                                            index = nextIndex,
                                            itemId = item.id,
                                            itemUri = item.uri,
                                            preferTunnel = preferTunnel,
                                            allowAudioPassthrough = allowAudioPassthrough,
                                            forcePowerSaver = forcePowerSaver,
                                            decision = decision,
                                        ),
                                ),
                            )
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        AppLog.warning(
                            category = "player.core2",
                            event = "next_item_preload_failed",
                            message = "YCore next-item route and decoder preheat failed; normal open remains available",
                            throwable = error,
                            attributes = mapOf("itemIndex" to nextIndex.toString()),
                        )
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
                        routeEvaluator.probePlatformForNativeAttempt(item)
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
                allowAudioPassthrough = false,
                frameRateSwitchMode = frameRateSwitchMode,
                forcedPlan = plan,
                preferredRemoteBufferTargetUs = preferredRemoteBufferTargetUs,
            )
        }

        suspend fun createChild(positionMs: Long): YPlayer? {
            pendingFailureKey = null
            pendingVerifiedRoute = null
            val bypassLearnedRouteMemory =
                shouldBypassLearnedYCoreRouteMemory(
                    manualRetry = bypassLearnedRouteMemoryOnce,
                    compatibilityRouteAvailable = fallbackRouteFactory != null,
                )
            bypassLearnedRouteMemoryOnce = false
            val rootItem = queueItems[currentIndex]
            val target =
                pendingAdaptiveTarget?.takeIf { it.rootUri == rootItem.uri }
                    ?: adaptiveFeedbackSink?.resolvePlaybackTarget(rootItem.uri, positionMs.coerceAtLeast(0L))
            pendingAdaptiveTarget = null
            adaptiveTarget = target
            val item = target?.let { rootItem.copy(uri = it.uri) } ?: rootItem
            val forcePowerSaver = currentThermalStatus() >= SEVERE_THERMAL_STATUS
            val tunnelAllowed =
                allowTunnel &&
                    audioDelayMs == 0L &&
                    item.drmConfiguration == null &&
                    item.allExternalSubtitles.isEmpty() &&
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
            if (warmedRoute != null) {
                preloadedNextRoute = null
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
                if (target == null && warmedRoute == null && !bypassLearnedRouteMemory && !forceSoftwareFallback) {
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
                    )
            if (forceSoftwareFallback) {
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
                    ) ?: return createInconclusiveSourceRoute(item, singleRequest)
            }
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
                        allowAudioPassthrough = false,
                        frameRateSwitchMode = frameRateSwitchMode,
                        forcedPlan = plan,
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

        fun attachChild(next: YPlayer) {
            stopChild()
            val attachedTarget = adaptiveTarget
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
            activeChild = next

            fun childIndex(): Int = queueItems.indexOfFirst { it.id == childItemId }.coerceAtLeast(0)
            val childFailureKey = pendingFailureKey
            val childVerifiedRoute = pendingVerifiedRoute
            var failureRecorded = false
            var successRecorded = false
            var learningRecorded = false
            var recoveryQueued = false
            var nextItemPreloadRequested = false
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
            next.setVideoOutput(output)
            childCollector =
                scope.launch {
                    next.state.collect { localChildState ->
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
                        if (reportedChildState.buffering && nextItemPreloadJob?.isActive == true) {
                            nextItemPreloadJob?.cancel()
                            nextItemPreloadJob = null
                            nextItemPreloadRequested = false
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
                            if (childFailureKey != null && category != null) {
                                failureLedger.recordFailure(childFailureKey, category)
                            }
                            // A local failure on the remembered route means the remembered facts
                            // no longer describe this media on this device; a transport, account
                            // or DRM failure says nothing about them.
                            if (category != null && category !in VERIFIED_ROUTE_NEUTRAL_FAILURES) {
                                childVerifiedRoute?.first?.let(verifiedRouteMemory::forget)
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
                            failureLedger.recordSuccess(childFailureKey)
                            childVerifiedRoute?.let { (verifiedItem, probe) ->
                                verifiedRouteMemory.recordVerified(verifiedItem, probe)
                            }
                        }
                        if (
                            !nextItemPreloadRequested &&
                            !childState.buffering &&
                            childState.playing &&
                            childState.phase == YPlaybackPhase.Ready
                        ) {
                            nextItemPreloadRequested = true
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
                            when (
                                YPlaybackRecoveryPolicy.decide(
                                    YPlaybackRecoveryContext(
                                        route = childState.diagnostics.route,
                                        category = childState.errorCategory,
                                        sameRouteAttempts = sameRouteRecoveryAttempts[recoveryKey] ?: 0,
                                        protectedContent = queueItems[childIndex()].drmConfiguration != null,
                                    ),
                                )
                            ) {
                                YPlaybackRecoveryAction.RetrySameRoute -> {
                                    sameRouteRecoveryAttempts[recoveryKey] =
                                        (sameRouteRecoveryAttempts[recoveryKey] ?: 0) + 1
                                    codecResetCounts[childIndex()] = (codecResetCounts[childIndex()] ?: 0) + 1
                                    commands.trySend(
                                        Command.RecoverSameRoute(
                                            index = childIndex(),
                                            positionMs = childState.positionMs,
                                            route = childState.diagnostics.route,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.DisableTunnel -> {
                                    commands.trySend(Command.FallbackFromTunnel(childIndex(), childState.positionMs))
                                    return@collect
                                }
                                YPlaybackRecoveryAction.FallbackToEnhanced -> {
                                    commands.trySend(
                                        Command.FallbackToEnhanced(childIndex(), childState.positionMs),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.FallbackToSoftware -> {
                                    commands.trySend(
                                        Command.FallbackToSoftware(childIndex(), childState.positionMs),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.Stop -> Unit
                            }
                        }
                        mutableState.value =
                            childState.copy(
                                currentIndex = childIndex(),
                                itemCount = queueItems.size,
                                playbackRequested = requestedPlay && childState.phase != YPlaybackPhase.Ended,
                                diagnostics =
                                    childState.diagnostics.copy(
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
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            !learningRecorded
                        ) {
                            recordLearning(childState, terminal = true)
                        }
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            request.autoNext &&
                            childIndex() + 1 < queueItems.size
                        ) {
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

        suspend fun rebuild(positionMs: Long) {
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
                    error = null,
                    errorCategory = null,
                    diagnostics =
                        it.diagnostics
                            .copy(
                                reason = "Evaluating YCore 2.0 route for queue item $currentIndex",
                            ).invalidateOutputEvidence(YOutputEvidenceResetReason.DecoderReconfigured),
                )
            }
            val next = createChild(positionMs)
            if (next == null) {
                publishUnavailable("No executable Core2 or compatibility route for item $currentIndex")
            } else {
                attachChild(next)
            }
        }

        try {
            for (command in commands) {
                try {
                    when (command) {
                        Command.Prepare -> rebuild(pendingPositionMs)
                        Command.Play -> {
                            requestedPlay = true
                            val active = child
                            if (active == null) rebuild(pendingPositionMs) else active.play()
                        }
                        Command.Pause -> {
                            requestedPlay = false
                            child?.pause()
                        }
                        Command.SeekPending -> {
                            seekCommandQueued.set(false)
                            val positionMs = pendingSeekMs.getAndSet(NO_PENDING_SEEK_MS)
                            if (positionMs >= 0L) {
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
                            if (active
                                    ?.state
                                    ?.value
                                    ?.diagnostics
                                    ?.route == YPlaybackRoute.NativeTunnel
                            ) {
                                allowTunnel = false
                                pendingPositionMs = globalChildPosition()
                                rebuild(pendingPositionMs)
                            }
                            child?.selectTrack(command.type, command.id)
                        }
                        is Command.SetVideoOutput -> {
                            output = command.output
                            val active = child
                            if (active != null) {
                                active.setVideoOutput(output)
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
                            if (preloadedNextRoute?.index != selectedIndex) preloadedNextRoute = null
                            pendingPositionMs = 0L
                            currentIndex = selectedIndex
                            sameRouteRecoveryAttempts.keys.removeAll { it.itemIndex == currentIndex }
                            codecResetCounts.remove(currentIndex)
                            allowTunnel = true
                            forceEnhancedFallback = false
                            forceSoftwareFallback = false
                            rebuild(0L)
                        }
                        Command.QueueUpdated -> {
                            nextItemPreloadJob?.cancel()
                            nextItemPreloadJob = null
                            preloadedNextRoute = null
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
                        is Command.NextItemPreloaded -> {
                            if (child !== command.fromChild) continue
                            nextItemPreloadJob = null
                            val item = queueItems.getOrNull(command.route.index)
                            if (
                                command.route.index == currentIndex + 1 &&
                                item != null &&
                                command.route.itemId == item.id &&
                                command.route.itemUri == item.uri
                            ) {
                                preloadedNextRoute = command.route
                                AppLog.info(
                                    category = "player.core2",
                                    event = "next_item_preloaded",
                                    message = "YCore warmed next-item source metadata and decoder configuration",
                                    attributes = mapOf("itemIndex" to command.route.index.toString()),
                                )
                            }
                        }
                        Command.AudioRouteChanged -> {
                            audioRouteChangeQueued.set(false)
                            val phase = mutableState.value.phase
                            when {
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
                                if (canRetryCore2RouteInPlace(command.route)) {
                                    // The child owns a serialized codec command queue. Reusing it
                                    // guarantees releaseMedia() finishes before the same decoder is
                                    // configured again; rebuilding here allowed the replacement
                                    // child to race the outgoing MediaCodec release on OEM devices.
                                    checkNotNull(active).retry()
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
                                )
                            ) {
                                // Runtime silent-output recovery is not a route change. Keep the
                                // active child and let its worker release, reopen and configure in
                                // strict order instead of constructing a competing codec instance.
                                active.retry()
                            } else {
                                rebuild(pendingPositionMs)
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    publishUnavailable(core2RouterFailureReason(failure))
                }
            }
        } finally {
            nextItemPreloadJob?.cancel()
            stopChild()
            routeEvaluator.closePreparedExtractor()
            routeEvaluator.closePreparedEnhancedDemux()
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

        data object AudioRouteChanged : Command

        data object ThermalPressure : Command

        data class NextItemPreloaded(
            val fromChild: YPlayer,
            val route: PreloadedNextRoute,
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
        ) : Command

        data class RecoverSameRoute(
            val index: Int,
            val positionMs: Long,
            val route: YPlaybackRoute,
        ) : Command

        data class FallbackToSoftware(
            val index: Int,
            val positionMs: Long,
        ) : Command

        data class FallbackToEnhanced(
            val index: Int,
            val positionMs: Long,
        ) : Command
    }

    private data class RouteRecoveryKey(
        val itemIndex: Int,
        val route: YPlaybackRoute,
        val category: YPlaybackFailureCategory?,
    )

    private data class PreloadedNextRoute(
        val index: Int,
        val itemId: String,
        val itemUri: String,
        val preferTunnel: Boolean,
        val allowAudioPassthrough: Boolean,
        val forcePowerSaver: Boolean,
        val decision: YCore2RouteDecision,
    ) {
        fun matches(
            index: Int,
            item: YMediaItem,
            preferTunnel: Boolean,
            allowAudioPassthrough: Boolean,
            forcePowerSaver: Boolean,
        ): Boolean =
            this.index == index &&
                itemId == item.id &&
                itemUri == item.uri &&
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

internal fun core2RouterFailureReason(failure: Throwable): String =
    "Core2 router failed at ${failure::class.simpleName ?: "unknown failure"}"

internal fun shouldRetryActiveNativeChildInPlace(
    nativeOnly: Boolean,
    phase: YPlaybackPhase,
    route: YPlaybackRoute,
): Boolean =
    nativeOnly &&
        phase != YPlaybackPhase.Failed &&
        canRetryCore2RouteInPlace(route)

internal fun canRetryCore2RouteInPlace(route: YPlaybackRoute): Boolean =
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
    if (
        plan.usesHdrFallback ||
        plan.inputHdrType == YHdrType.DolbyVision ||
        plan.outputHdrType == YHdrType.DolbyVision
    ) {
        return false
    }
    return runCatching {
        AndroidFfmpegDemuxer().let { demuxer ->
            demuxer.available && demuxer.softwareDecodeAvailable
        }
    }.getOrDefault(false)
}

internal fun YPlaybackPlan.withNativeGpuFallbackTruth(probe: YNativeGpuRuntimeProbe): YPlaybackPlan {
    if (route != YPlaybackRoute.GpuEnhanced) return this
    val reason =
        probe.firstMissingRequirement()?.let { requirement ->
            "$reason; native Vulkan blocked at $requirement"
        } ?: "$reason; native Vulkan presentation executor is not installed"
    return copy(reason = reason)
}
