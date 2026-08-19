package com.yfuse.core2.android

import android.content.Context
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

/**
 * Per-item Core2 router. It never assumes one queue shares one codec/HDR/audio route.
 *
 * Each selected item is probed independently and receives NativeTunnel, NativeDirect, or
 * NativeEnhanced.
 * If Core2 cannot prove an executable route, state becomes Failed/Unknown so the product-level
 * Legacy fallback can take over without poisoning any decoder-specific failure memory.
 */
internal class AndroidAdaptiveCore2YPlayer(
    private val context: Context,
    private val request: YPlayerOpenRequest,
    private val routeEvaluator: AndroidCore2RouteEvaluator = AndroidCore2RouteEvaluator(context),
) : YPlayer {
    private val mutableState =
        MutableStateFlow(
            YPlayerState(
                phase = YPlaybackPhase.Idle,
                playbackRequested = request.autoPlay,
                positionMs = request.startPositionMs,
                currentIndex = request.startIndex,
                itemCount = request.items.size,
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

    @Volatile
    private var released = false

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

    override fun selectTrack(type: YTrackType, id: String) = send(Command.SelectTrack(type, id))

    override fun selectItem(index: Int) {
        if (released || index !in request.items.indices) return
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

    override fun currentPositionMs(): Long = mutableState.value.positionMs

    override fun retry() = send(Command.Retry)

    override fun release() {
        if (released) return
        released = true
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

    private suspend fun runLoop() {
        var currentIndex = request.startIndex
        var child: YPlayer? = null
        var childCollector: Job? = null
        var output: YVideoOutput? = null
        var requestedPlay = request.autoPlay
        var speed = 1f
        var pendingPositionMs = request.startPositionMs
        var allowTunnel = true

        fun stopChild() {
            childCollector?.cancel()
            childCollector = null
            child?.release()
            child = null
        }

        fun publishUnavailable(reason: String) {
            stopChild()
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = requestedPlay,
                    buffering = false,
                    error = "YCore 2.0 当前没有可证明安全的原生路径，交回 Legacy",
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
            val item = request.items[currentIndex]
            val tunnelAllowed = allowTunnel && kotlin.math.abs(speed - 1f) <= TUNNEL_SPEED_EPSILON
            val decision = routeEvaluator.evaluate(item, preferTunnel = tunnelAllowed) ?: return null
            val singleRequest =
                YPlayerOpenRequest(
                    items = listOf(item),
                    startIndex = 0,
                    startPositionMs = positionMs.coerceAtLeast(0L),
                    autoPlay = requestedPlay,
                    autoNext = false,
                )
            return when {
                tunnelAllowed && decision.nativeTunnelExecutable ->
                    AndroidNativeTunnelYPlayer(context, singleRequest, routeEvaluator)
                decision.nativeDirectExecutable && !decision.plan.usesHdrFallback ->
                    AndroidNativeDirectYPlayer(context, singleRequest)
                decision.nativeEnhancedExecutable ->
                    AndroidNativeEnhancedYPlayer(context, singleRequest, routeEvaluator)
                else -> null
            }
        }

        fun attachChild(next: YPlayer) {
            stopChild()
            child = next
            val childIndex = currentIndex
            next.setSpeed(speed)
            next.setVideoOutput(output)
            childCollector =
                scope.launch {
                    next.state.collect { childState ->
                        if (
                            childState.phase == YPlaybackPhase.Failed &&
                            childState.diagnostics.route == YPlaybackRoute.NativeTunnel
                        ) {
                            commands.trySend(Command.FallbackFromTunnel(childIndex, childState.positionMs))
                            return@collect
                        }
                        mutableState.value =
                            childState.copy(
                                currentIndex = childIndex,
                                itemCount = request.items.size,
                                playbackRequested = requestedPlay && childState.phase != YPlaybackPhase.Ended,
                            )
                        if (
                            childState.phase == YPlaybackPhase.Ended &&
                            request.autoNext &&
                            childIndex + 1 < request.items.size
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
                publishUnavailable("No executable NativeTunnel/NativeDirect/NativeEnhanced route for item $currentIndex")
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
                                active?.state?.value?.diagnostics?.route == YPlaybackRoute.NativeTunnel &&
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
                            if (active?.state?.value?.diagnostics?.route == YPlaybackRoute.NativeTunnel) {
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
                            } else if (output != null && mutableState.value.phase != YPlaybackPhase.Failed) {
                                rebuild(pendingPositionMs)
                            }
                        }
                        is Command.SelectItem -> {
                            pendingPositionMs = 0L
                            currentIndex = command.index
                            allowTunnel = true
                            rebuild(0L)
                        }
                        is Command.FallbackFromTunnel -> {
                            if (
                                command.index == currentIndex &&
                                child?.state?.value?.diagnostics?.route == YPlaybackRoute.NativeTunnel
                            ) {
                                allowTunnel = false
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

        data class Seek(val positionMs: Long) : Command
        data class SetSpeed(val speed: Float) : Command
        data class SelectTrack(val type: YTrackType, val id: String) : Command
        data class SetVideoOutput(val output: YVideoOutput?) : Command
        data class SelectItem(val index: Int) : Command
        data class FallbackFromTunnel(val index: Int, val positionMs: Long) : Command
    }
}

internal class AndroidCore2PlayerFactory(
    private val context: Context,
) : YPlayerFactory {
    override fun create(request: YPlayerOpenRequest): YPlayer =
        AndroidAdaptiveCore2YPlayer(context, request)
}

private inline fun MutableStateFlow<YPlayerState>.updateState(
    transform: (YPlayerState) -> YPlayerState,
) {
    value = transform(value)
}

private const val TUNNEL_SPEED_EPSILON = 0.001f
