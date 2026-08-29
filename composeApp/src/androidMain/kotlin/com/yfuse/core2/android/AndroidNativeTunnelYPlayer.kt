package com.yfuse.core2.android

import android.content.Context
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerOpenRequest
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import com.yfuse.core2.render.YFrameRateSwitchMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Unified YPlayer wrapper for Android multimedia tunneling.
 *
 * Tunnel is deliberately narrow: platform demux, a proven tunneled video decoder, HW_AV_SYNC audio,
 * 1.0x playback, and direct sideband Surface output. AdaptiveCore2 owns every escape hatch back to
 * NativeDirect when the user asks for a feature that breaks that hardware contract.
 */
internal class AndroidNativeTunnelYPlayer(
    context: Context,
    private val request: YPlayerOpenRequest,
    private val routeEvaluator: AndroidCore2RouteEvaluator = AndroidCore2RouteEvaluator(context),
    private val allowAudioPassthrough: Boolean = true,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
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
                        route = YPlaybackRoute.NativeTunnel,
                        demuxer = "MediaExtractor",
                        renderer = "MediaCodec tunnel sideband + HW_AV_SYNC AudioTrack",
                        reason = "YCore 2.0 NativeTunnel",
                    ),
            ),
        )
    override val state: StateFlow<YPlayerState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker: Job = scope.launch { runLoop() }

    @Volatile
    private var released = false

    override fun prepare() = send(Command.Prepare)

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released || output != null && output !is AndroidSurfaceVideoOutput) return false
        commands.trySend(Command.SetVideoOutput(output as AndroidSurfaceVideoOutput?))
        return true
    }

    override fun play() {
        if (released) return
        mutableState.updateState {
            it.copy(playbackRequested = true, error = null, errorCategory = null)
        }
        commands.trySend(Command.Play)
    }

    override fun pause() {
        if (released) return
        mutableState.updateState {
            it.copy(playbackRequested = false, playing = false, buffering = false)
        }
        commands.trySend(Command.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val target = positionMs.coerceAtLeast(0L)
        mutableState.updateState { it.copy(positionMs = target, buffering = it.playbackRequested) }
        commands.trySend(Command.Seek(target * MICROS_PER_MILLISECOND))
    }

    override fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed <= 0f) return
        if (kotlin.math.abs(speed - 1f) <= SPEED_EPSILON) {
            mutableState.updateState { it.copy(speed = 1f) }
            return
        }
        // AdaptiveCore2 observes this typed capability escape and rebuilds the item as Direct.
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Failed,
                playing = false,
                buffering = false,
                error = "硬件 Tunnel 不支持变速，切换普通原生路径",
                errorCategory = YPlaybackFailureCategory.Unknown,
                diagnostics =
                    it.diagnostics.copy(
                        reason = "NativeTunnel supports 1.0x only; route handover required",
                    ),
            )
        }
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (released) return
        val selected =
            when (type) {
                YTrackType.Audio -> mutableState.value.audioTracks.any { it.id == id && it.selected }
                YTrackType.Subtitle -> false
            }
        if (selected) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Failed,
                playing = false,
                buffering = false,
                error = "Tunnel 当前不支持热切轨道，切换普通原生路径",
                errorCategory = YPlaybackFailureCategory.Unknown,
                diagnostics =
                    it.diagnostics.copy(reason = "Track change requires NativeDirect handover"),
            )
        }
    }

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

    override fun retry() = send(Command.Prepare)

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
        val session = AndroidNativeTunnelSession(appContext, frameRateSwitchMode = frameRateSwitchMode)
        var surfaceOutput: AndroidSurfaceVideoOutput? = null
        var currentIndex = request.startIndex
        var requestedPlay = request.autoPlay
        var prepared = false
        var lastPublishNs = 0L
        var nativeDolbyVisionRoute = false

        fun publishFailure(failure: Throwable) {
            session.close()
            prepared = false
            nativeDolbyVisionRoute = false
            requestedPlay = false
            val typed = failure as? YPlaybackException
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Failed,
                    playing = false,
                    playbackRequested = false,
                    buffering = false,
                    error = "YCore 2.0 Tunnel 路径失败，切换普通原生路径",
                    errorCategory = typed?.category ?: YPlaybackFailureCategory.Unknown,
                    diagnostics =
                        it.diagnostics.copy(
                            videoOutput = "停止",
                            audioOutput = "停止",
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            reason =
                                typed?.stage?.let { stage -> "NativeTunnel failed at ${stage.name}" }
                                    ?: "NativeTunnel failed before typed-stage classification",
                        ),
                )
            }
        }

        fun prepareCurrent(positionUs: Long) {
            nativeDolbyVisionRoute = false
            val surface = surfaceOutput?.surface?.takeIf { it.isValid }
            if (surface == null) {
                prepared = false
                mutableState.updateState {
                    it.copy(
                        phase = YPlaybackPhase.Preparing,
                        playing = false,
                        buffering = requestedPlay,
                        diagnostics = it.diagnostics.copy(videoOutput = "等待 Surface"),
                    )
                }
                return
            }
            val item = request.items[currentIndex]
            val decision =
                routeEvaluator.evaluate(
                    item,
                    preferTunnel = true,
                    allowAudioPassthrough = allowAudioPassthrough,
                )
            check(decision?.nativeTunnelExecutable == true) {
                "Media item is not eligible for YCore NativeTunnel"
            }
            nativeDolbyVisionRoute =
                decision.probe.dolbyVisionConfig != null &&
                    decision.plan.outputHdrType == com.yfuse.core2.capability.YHdrType.DolbyVision
            session.open(
                source = item.toAndroidTunnelSource(),
                surface = surface,
                startPositionUs = positionUs.coerceAtLeast(0L),
                decoderName = decision.plan.decoderName,
                runtimeCapabilityKey = decision.runtimeCapabilityKey(),
                dolbyVisionConfig = decision.probe.dolbyVisionConfig,
            )
            prepared = true
            val snapshot = session.snapshot()
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Ready,
                    playing = false,
                    buffering = requestedPlay,
                    durationMs = snapshot.durationUs / MICROS_PER_MILLISECOND,
                    currentIndex = currentIndex,
                    itemCount = request.items.size,
                    speed = 1f,
                    audioTracks =
                        listOf(
                            YTrack(
                                id = PRIMARY_AUDIO_TRACK_ID,
                                type = YTrackType.Audio,
                                label = "Primary audio",
                                selected = true,
                            ),
                        ),
                    error = null,
                    errorCategory = null,
                    diagnostics =
                        it.diagnostics.copy(
                            route = YPlaybackRoute.NativeTunnel,
                            container = decision.probe.playbackRequest.container.name,
                            decoder =
                                listOfNotNull(snapshot.videoDecoderName, snapshot.audioDecoderName)
                                    .joinToString(" + "),
                            renderer = "Tunnel sideband + HW_AV_SYNC AudioTrack",
                            videoCodec = decision.probe.videoMime,
                            videoWidth = decision.probe.playbackRequest.video.width,
                            videoHeight = decision.probe.playbackRequest.video.height,
                            frameRate = decision.probe.playbackRequest.video.frameRate,
                            audioCodec = decision.probe.audioMime.orEmpty(),
                            dynamicRange = decision.plan.outputHdrType.name,
                            videoOutput = "等待 Tunnel 首帧",
                            audioOutput = "等待 HW_AV_SYNC 时钟",
                            videoOutputVerified = false,
                            audioOutputVerified = false,
                            // Promoted only after the tunnel decoder emits a frame-render callback.
                            dolbyVisionOutput = false,
                            immersiveAudioCarrierOutput = false,
                            dolbyAtmosOutput = false,
                            spatialAudioOutput = false,
                            headTrackingAvailable = false,
                            reason = decision.plan.reason,
                        ),
                )
            }
            if (requestedPlay) session.play()
        }

        fun publishSnapshot(force: Boolean = false) {
            if (!prepared) return
            val now = System.nanoTime()
            if (!force && now - lastPublishNs < STATE_PUBLISH_INTERVAL_NS) return
            lastPublishNs = now
            val snapshot = session.snapshot()
            if (snapshot.ended) requestedPlay = false
            mutableState.updateState {
                it.copy(
                    phase = if (snapshot.ended) YPlaybackPhase.Ended else YPlaybackPhase.Ready,
                    playing = snapshot.playing,
                    buffering = snapshot.buffering,
                    playbackRequested = requestedPlay,
                    positionMs = snapshot.positionUs / MICROS_PER_MILLISECOND,
                    diagnostics =
                        it.diagnostics.copy(
                            decoder =
                                listOfNotNull(snapshot.videoDecoderName, snapshot.audioDecoderName)
                                    .joinToString(" + "),
                            videoOutput =
                                if (snapshot.videoOutputVerified) {
                                    "Tunnel sideband 已渲染"
                                } else {
                                    "等待 Tunnel 首帧"
                                },
                            audioOutput =
                                if (snapshot.audioClockReady) {
                                    "HW_AV_SYNC AudioTrack"
                                } else {
                                    "等待 HW_AV_SYNC 时钟"
                                },
                            videoOutputVerified = snapshot.videoOutputVerified,
                            audioOutputVerified = snapshot.audioClockReady,
                            dolbyVisionOutput = snapshot.videoOutputVerified && nativeDolbyVisionRoute,
                        ),
                )
            }
        }

        try {
            while (scope.isActive) {
                var handled = false
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    handled = true
                    try {
                        when (command) {
                            Command.Prepare ->
                                prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                            Command.Play -> {
                                requestedPlay = true
                                if (prepared) {
                                    session.play()
                                } else {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                }
                            }
                            Command.Pause -> {
                                requestedPlay = false
                                if (prepared) session.pause()
                            }
                            is Command.Seek -> {
                                if (prepared) session.seekTo(command.positionUs)
                            }
                            is Command.SetVideoOutput -> {
                                val previous = surfaceOutput
                                surfaceOutput = command.output
                                val next = command.output?.surface?.takeIf { it.isValid }
                                if (next == null) {
                                    val positionUs =
                                        if (prepared) {
                                            session.snapshot().positionUs
                                        } else {
                                            mutableState.value.positionMs * MICROS_PER_MILLISECOND
                                        }
                                    session.close()
                                    prepared = false
                                    mutableState.updateState {
                                        it.copy(
                                            playing = false,
                                            buffering = requestedPlay,
                                            positionMs = positionUs / MICROS_PER_MILLISECOND,
                                            diagnostics = it.diagnostics.copy(videoOutput = "等待 Surface"),
                                        )
                                    }
                                } else if (prepared && previous?.surface?.isValid == true) {
                                    session.setOutputSurface(next)
                                } else if (!prepared) {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                }
                            }
                            is Command.SelectItem -> {
                                currentIndex = command.index
                                session.close()
                                prepared = false
                                prepareCurrent(0L)
                            }
                        }
                        publishSnapshot(force = true)
                    } catch (failure: Throwable) {
                        publishFailure(failure)
                    }
                }

                val didWork =
                    if (prepared && requestedPlay) {
                        try {
                            session.pump()
                        } catch (failure: Throwable) {
                            publishFailure(failure)
                            false
                        }
                    } else {
                        false
                    }
                publishSnapshot()
                if (!handled && !didWork) delay(PUMP_IDLE_DELAY_MS)
            }
        } finally {
            session.close()
        }
    }

    private sealed interface Command {
        data object Prepare : Command

        data object Play : Command

        data object Pause : Command

        data class Seek(
            val positionUs: Long,
        ) : Command

        data class SetVideoOutput(
            val output: AndroidSurfaceVideoOutput?,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

private fun YMediaItem.toAndroidTunnelSource(): YAndroidMediaSource =
    YAndroidMediaSource(
        uri = uri,
        headers = headers,
        cacheIdentity = cacheIdentity,
        cacheMaximumBytes = cacheMaximumBytes,
    )

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    value = transform(value)
}

private const val PRIMARY_AUDIO_TRACK_ID = "audio:tunnel-primary"
private const val MICROS_PER_MILLISECOND = 1_000L
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
private const val SPEED_EPSILON = 0.0001f
