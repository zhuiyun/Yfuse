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
import com.yfuse.core2.api.YMediaItem
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
    private val onRelease: () -> Unit = {},
) : YPlayer {
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
    private val audioCallbackHandler = Handler(Looper.getMainLooper())
    private val audioRouteChangeQueued = AtomicBoolean(false)
    private val audioDeviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = queueAudioRouteChange()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = queueAudioRouteChange()
        }

    @Volatile
    private var released = false

    @Volatile
    private var activeChild: YPlayer? = null

    init {
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
        mutableState.updateState { it.copy(positionMs = target) }
        commands.trySend(Command.Seek(target))
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
        commands.trySend(Command.SelectItem(index))
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

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() = send(Command.Retry)

    override fun release() {
        if (released) return
        released = true
        audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
        commands.close()
        worker.cancel()
        scope.cancel()
        runCatching(onRelease)
        mutableState.value =
            mutableState.value.copy(
                phase = YPlaybackPhase.Idle,
                playing = false,
                playbackRequested = false,
                buffering = false,
            )
    }

    private fun send(command: Command) {
        if (!released) commands.trySend(command)
    }

    private fun queueAudioRouteChange() {
        if (!released && audioRouteChangeQueued.compareAndSet(false, true)) {
            if (commands.trySend(Command.AudioRouteChanged).isFailure) {
                audioRouteChangeQueued.set(false)
            }
        }
    }

    private fun currentBatteryPermille(): Int {
        val percent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return -1
        return if (percent in 0..100) percent * 10 else -1
    }

    private fun currentThermalStatus(): Int = if (Build.VERSION.SDK_INT >= 29) powerManager?.currentThermalStatus ?: 0 else 0

    private suspend fun runLoop() {
        var currentIndex = request.startIndex
        var child: YPlayer? = null
        var childCollector: Job? = null
        var output: YVideoOutput? = null
        var requestedPlay = request.autoPlay
        var speed = 1f
        var pendingPositionMs = request.startPositionMs
        var allowTunnel = true
        var forceSoftwareFallback = false
        var pendingFailureKey: YCore2FailureKey? = null
        var finalizeChildLearning: (() -> Unit)? = null
        val sameRouteRecoveryAttempts = mutableMapOf<RouteRecoveryKey, Int>()
        val codecResetCounts = mutableMapOf<Int, Int>()

        fun stopChild() {
            finalizeChildLearning?.invoke()
            finalizeChildLearning = null
            childCollector?.cancel()
            childCollector = null
            child?.release()
            child = null
            activeChild = null
        }

        fun publishUnavailable(reason: String) {
            stopChild()
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = requestedPlay,
                    buffering = false,
                    error = "YCore 2.0 当前没有可证明安全的本地路径，已停止播放",
                    errorCategory = YPlaybackFailureCategory.Unknown,
                    diagnostics =
                        it.diagnostics.copy(
                            route = YPlaybackRoute.Legacy,
                            reason = reason,
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            dolbyAtmosOutput = false,
                        ),
                )
            }
        }

        fun createChild(positionMs: Long): YPlayer? {
            pendingFailureKey = null
            val item = queueItems[currentIndex]
            val tunnelAllowed =
                allowTunnel &&
                    item.drmConfiguration == null &&
                    item.externalSubtitle == null &&
                    kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            val singleRequest =
                YPlayerOpenRequest(
                    items = listOf(item),
                    startIndex = 0,
                    startPositionMs = positionMs.coerceAtLeast(0L),
                    autoPlay = requestedPlay,
                    autoNext = false,
                )
            if (item.disc != null) {
                return discRouteFactory?.create(
                    item = item,
                    request = singleRequest,
                    startSpeed = speed,
                    forceSoftwareDecode = forceSoftwareFallback,
                )
            }
            var decision =
                routeEvaluator.evaluate(
                    item,
                    preferTunnel = tunnelAllowed,
                    allowAudioPassthrough = allowAudioPassthrough,
                ) ?: return null
            if (
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
                    ) ?: return null
            }
            val learnedAdvice = learningEngine.advice(decision.toFailureKey().toLearningKey())
            if (
                forceSoftwareFallback ||
                failureLedger.isBlocked(decision.toFailureKey()) ||
                learnedAdvice == YLearnedRouteAdvice.Avoid
            ) {
                decision =
                    decision.copy(
                        plan =
                            decision.plan.toSoftwareFallbackPlan(
                                if (forceSoftwareFallback) {
                                    "A previous local route failed at runtime"
                                } else {
                                    "Device failure and quality memory skipped the planned local route"
                                },
                            ),
                    )
            }
            if (failureLedger.isBlocked(decision.toFailureKey())) return null
            val plan = decision.plan
            pendingFailureKey = decision.toFailureKey()
            return when {
                !forceSoftwareFallback && tunnelAllowed && decision.nativeTunnelExecutable ->
                    AndroidNativeTunnelYPlayer(
                        context,
                        singleRequest,
                        routeEvaluator,
                        allowAudioPassthrough,
                        frameRateSwitchMode,
                    )
                !forceSoftwareFallback &&
                    decision.nativeDirectExecutable &&
                    !decision.plan.usesHdrFallback ->
                    AndroidNativeDirectYPlayer(
                        context,
                        singleRequest,
                        decision.plan.decoderName,
                        decision.runtimeCapabilityKey(),
                        decision.plan.audioPath,
                        frameRateSwitchMode,
                    )
                !forceSoftwareFallback && decision.nativeEnhancedExecutable ->
                    AndroidNativeEnhancedYPlayer(
                        context,
                        singleRequest,
                        routeEvaluator,
                        allowAudioPassthrough,
                        frameRateSwitchMode,
                    )
                plan.route == YPlaybackRoute.SoftwareFallback &&
                    plan.demuxPath == YDemuxPath.Enhanced &&
                    item.drmConfiguration == null &&
                    yCoreSoftwarePlanExecutable(plan) ->
                    AndroidNativeEnhancedYPlayer(
                        context = context,
                        request = singleRequest,
                        routeEvaluator = routeEvaluator,
                        allowAudioPassthrough = false,
                        frameRateSwitchMode = frameRateSwitchMode,
                        forcedPlan = plan,
                    )
                plan.route == YPlaybackRoute.GpuEnhanced ->
                    AndroidYCoreGpuRuntime.probe(
                        context,
                        yCoreGpuEvidenceKey(decision.probe.playbackRequest, plan),
                    ).let { routeGpuProbe ->
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
                                allowAudioPassthrough = false,
                                frameRateSwitchMode = frameRateSwitchMode,
                                forcedPlan = nativeGpuPlan,
                            )
                        } else {
                            fallbackRouteFactory?.create(
                                item,
                                singleRequest,
                                plan.withNativeGpuFallbackTruth(routeGpuProbe),
                                speed,
                            )
                        }
                    }
                plan.route == YPlaybackRoute.SoftwareFallback ->
                    fallbackRouteFactory?.create(item, singleRequest, plan, speed)
                else -> null
            }
        }

        fun attachChild(next: YPlayer) {
            stopChild()
            child = next
            activeChild = next
            val childIndex = currentIndex
            val childFailureKey = pendingFailureKey
            var failureRecorded = false
            var successRecorded = false
            var learningRecorded = false
            var recoveryQueued = false
            val learningStartPositionMs = next.currentPositionMs()
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
                                        (codecResetCounts[childIndex] ?: 0),
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
            next.setVideoOutput(output)
            childCollector =
                scope.launch {
                    next.state.collect { childState ->
                        if (childState.phase == YPlaybackPhase.Failed && !failureRecorded) {
                            failureRecorded = true
                            val category = childState.errorCategory
                            if (childFailureKey != null && category != null) {
                                failureLedger.recordFailure(childFailureKey, category)
                            }
                            recordLearning(childState, terminal = true)
                        }
                        if (
                            !successRecorded &&
                            childFailureKey != null &&
                            childState.diagnostics.videoOutputVerified &&
                            (childState.audioTracks.isEmpty() || childState.diagnostics.audioOutputVerified)
                        ) {
                            successRecorded = true
                            failureLedger.recordSuccess(childFailureKey)
                        }
                        if (
                            childState.phase == YPlaybackPhase.Failed &&
                            failureRecorded &&
                            !recoveryQueued
                        ) {
                            recoveryQueued = true
                            val recoveryKey =
                                RouteRecoveryKey(
                                    itemIndex = childIndex,
                                    route = childState.diagnostics.route,
                                    category = childState.errorCategory,
                                )
                            when (
                                YPlaybackRecoveryPolicy.decide(
                                    YPlaybackRecoveryContext(
                                        route = childState.diagnostics.route,
                                        category = childState.errorCategory,
                                        sameRouteAttempts = sameRouteRecoveryAttempts[recoveryKey] ?: 0,
                                        protectedContent = queueItems[childIndex].drmConfiguration != null,
                                    ),
                                )
                            ) {
                                YPlaybackRecoveryAction.RetrySameRoute -> {
                                    sameRouteRecoveryAttempts[recoveryKey] =
                                        (sameRouteRecoveryAttempts[recoveryKey] ?: 0) + 1
                                    codecResetCounts[childIndex] = (codecResetCounts[childIndex] ?: 0) + 1
                                    commands.trySend(
                                        Command.RecoverSameRoute(
                                            index = childIndex,
                                            positionMs = childState.positionMs,
                                            route = childState.diagnostics.route,
                                        ),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.DisableTunnel -> {
                                    commands.trySend(Command.FallbackFromTunnel(childIndex, childState.positionMs))
                                    return@collect
                                }
                                YPlaybackRecoveryAction.FallbackToSoftware -> {
                                    commands.trySend(
                                        Command.FallbackToSoftware(childIndex, childState.positionMs),
                                    )
                                    return@collect
                                }
                                YPlaybackRecoveryAction.Stop -> Unit
                            }
                        }
                        mutableState.value =
                            childState.copy(
                                currentIndex = childIndex,
                                itemCount = queueItems.size,
                                playbackRequested = requestedPlay && childState.phase != YPlaybackPhase.Ended,
                                diagnostics =
                                    childState.diagnostics.copy(
                                        codecResetCount =
                                            childState.diagnostics.codecResetCount +
                                                (codecResetCounts[childIndex] ?: 0),
                                    ),
                            )
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            !learningRecorded
                        ) {
                            recordLearning(childState, terminal = true)
                        }
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            request.autoNext &&
                            childIndex + 1 < queueItems.size
                        ) {
                            commands.trySend(Command.SelectItem(childIndex + 1))
                        }
                    }
                }
            next.prepare()
            if (requestedPlay) next.play()
        }

        fun rebuild(positionMs: Long) {
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
                        it.diagnostics.copy(
                            reason = "Evaluating YCore 2.0 route for queue item $currentIndex",
                        ),
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
                        is Command.Seek -> {
                            pendingPositionMs = command.positionMs
                            child?.seekTo(command.positionMs)
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
                                pendingPositionMs = active.currentPositionMs()
                                rebuild(pendingPositionMs)
                            } else {
                                active?.setSpeed(speed)
                            }
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
                                pendingPositionMs = active.currentPositionMs()
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
                            pendingPositionMs = 0L
                            currentIndex = command.index
                            sameRouteRecoveryAttempts.keys.removeAll { it.itemIndex == currentIndex }
                            codecResetCounts.remove(currentIndex)
                            allowTunnel = true
                            forceSoftwareFallback = false
                            rebuild(0L)
                        }
                        Command.QueueExtended -> {
                            mutableState.updateState { it.copy(itemCount = queueItems.size) }
                        }
                        Command.AudioRouteChanged -> {
                            audioRouteChangeQueued.set(false)
                            if (child != null || mutableState.value.phase != YPlaybackPhase.Idle) {
                                pendingPositionMs = child?.currentPositionMs() ?: mutableState.value.positionMs
                                forceSoftwareFallback = false
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
                            val activeState = child?.state?.value
                            if (
                                command.index == currentIndex &&
                                activeState?.phase == YPlaybackPhase.Failed &&
                                activeState.diagnostics.route == command.route
                            ) {
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
                                forceSoftwareFallback = true
                                pendingPositionMs = command.positionMs
                                rebuild(pendingPositionMs)
                            }
                        }
                        Command.Retry -> {
                            sameRouteRecoveryAttempts.keys.removeAll { it.itemIndex == currentIndex }
                            codecResetCounts.remove(currentIndex)
                            forceSoftwareFallback = false
                            rebuild(mutableState.value.positionMs)
                        }
                    }
                } catch (_: Throwable) {
                    publishUnavailable("Core2 router failed before a child route became active")
                }
            }
        } finally {
            stopChild()
        }
    }

    private sealed interface Command {
        data object Prepare : Command

        data object Play : Command

        data object Pause : Command

        data object Retry : Command

        data object QueueExtended : Command

        data object AudioRouteChanged : Command

        data class Seek(
            val positionMs: Long,
        ) : Command

        data class SetSpeed(
            val speed: Float,
        ) : Command

        data class SelectTrack(
            val type: YTrackType,
            val id: String,
        ) : Command

        data class SetVideoOutput(
            val output: YVideoOutput?,
        ) : Command

        data class SelectItem(
            val index: Int,
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
    }

    private data class RouteRecoveryKey(
        val itemIndex: Int,
        val route: YPlaybackRoute,
        val category: YPlaybackFailureCategory?,
    )
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
    value = transform(value)
}

private const val TUNNEL_SPEED_EPSILON = 0.001f
private const val MIN_LEARNING_PLAYBACK_MS = 30_000L

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
