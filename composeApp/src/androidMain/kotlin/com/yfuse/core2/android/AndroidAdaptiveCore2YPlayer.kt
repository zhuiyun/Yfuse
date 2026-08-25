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
import com.yfuse.core2.learning.YLearnedRouteAdvice
import com.yfuse.core2.learning.YPlaybackLearningEngine
import com.yfuse.core2.learning.YPlaybackLearningKey
import com.yfuse.core2.learning.YPlaybackObservation
import com.yfuse.core2.legacy.AndroidMpvCore2FallbackFactory
import com.yfuse.core2.quirk.YCore2FailureKey
import com.yfuse.core2.quirk.YCore2FailureLedger
import com.yfuse.core2.render.YFrameRateSwitchMode
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

    private fun currentThermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) powerManager?.currentThermalStatus ?: 0 else 0

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
                    error = "YCore 2.0 当前没有可证明安全的本地路径，交回 Legacy",
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
                plan.route == YPlaybackRoute.GpuEnhanced ||
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
                                    childState.diagnostics.codecResetCount,
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
                            childState.diagnostics.route == YPlaybackRoute.NativeTunnel
                        ) {
                            commands.trySend(Command.FallbackFromTunnel(childIndex, childState.positionMs))
                            return@collect
                        }
                        if (
                            childState.phase == YPlaybackPhase.Failed &&
                            childState.diagnostics.route != YPlaybackRoute.SoftwareFallback &&
                            childState.errorCategory.allowsCore2LocalSoftwareFallback()
                        ) {
                            commands.trySend(
                                Command.FallbackToSoftware(childIndex, childState.positionMs),
                            )
                            return@collect
                        }
                        mutableState.value =
                            childState.copy(
                                currentIndex = childIndex,
                                itemCount = queueItems.size,
                                playbackRequested = requestedPlay && childState.phase != YPlaybackPhase.Ended,
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
                        Command.Retry -> rebuild(mutableState.value.positionMs)
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

        data class FallbackToSoftware(
            val index: Int,
            val positionMs: Long,
        ) : Command
    }
}

internal class AndroidCore2PlayerFactory(
    private val context: Context,
) : YPlayerFactory {
    override fun create(request: YPlayerOpenRequest): YPlayer {
        val compatibilityFactory = AndroidMpvCore2FallbackFactory(context)
        return AndroidAdaptiveCore2YPlayer(
            context = context,
            request = request,
            fallbackRouteFactory = compatibilityFactory,
            discRouteFactory = compatibilityFactory,
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

internal fun YPlaybackPlan.toSoftwareFallbackPlan(reason: String): YPlaybackPlan =
    copy(
        route = YPlaybackRoute.SoftwareFallback,
        demuxPath = YDemuxPath.Software,
        decodePath = YDecodePath.Software,
        renderPath = YRenderPath.Gpu,
        decoderName = null,
        nativeAudio = false,
        audioPath = YAudioOutputPath.None,
        usesHdrFallback = false,
        reason = reason,
    )
