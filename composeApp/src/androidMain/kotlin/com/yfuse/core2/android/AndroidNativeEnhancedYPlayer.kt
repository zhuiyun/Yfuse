package com.yfuse.core2.android

import android.content.Context
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
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.demux.YDemuxOpenResult
import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.render.YFrameRateSwitchMode
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
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
 * Unified YPlayer wrapper for the Phase-3 NativeEnhanced graph.
 *
 * It is still opt-in: production routing must explicitly choose this player only after the enhanced
 * probe and strategy return an executable NativeEnhanced plan. Legacy remains the recovery path.
 */
internal class AndroidNativeEnhancedYPlayer(
    context: Context,
    private val request: YPlayerOpenRequest,
    private val routeEvaluator: AndroidCore2RouteEvaluator = AndroidCore2RouteEvaluator(context),
    private val allowAudioPassthrough: Boolean = true,
    private val frameRateSwitchMode: YFrameRateSwitchMode = YFrameRateSwitchMode.SeamlessOnly,
    private val forcedPlan: YPlaybackPlan? = null,
) : YPlayer {
    private val appContext = context.applicationContext
    private val capabilityProvider = AndroidYCapabilityProvider(context)
    private val externalSubtitleLoader = AndroidExternalSubtitleLoader(context)
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
                        route = forcedPlan?.route ?: YPlaybackRoute.NativeEnhanced,
                        demuxer = "FFmpeg 8.1 / libavformat",
                        renderer = if (forcedPlan?.route == YPlaybackRoute.GpuEnhanced) "Vulkan + AudioTrack" else "Surface + AudioTrack",
                        reason = "YCore 2.0 NativeEnhanced opt-in path",
                    ),
            ),
        )
    override val state: StateFlow<YPlayerState> = mutableState.asStateFlow()
    override val playbackRequested: Boolean get() = mutableState.value.playbackRequested

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker: Job = scope.launch { runLoop() }

    @Volatile
    private var released = false

    override fun prepare() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                buffering = it.playbackRequested,
                error = null,
                errorCategory = null,
            )
        }
        commands.trySend(Command.Prepare)
    }

    override fun setVideoOutput(output: YVideoOutput?): Boolean {
        if (released) return false
        if (output != null && output !is AndroidSurfaceVideoOutput) return false
        commands.trySend(Command.SetVideoOutput(output as AndroidSurfaceVideoOutput?))
        return true
    }

    override fun play() {
        if (released) return
        mutableState.updateState {
            it.copy(
                playbackRequested = true,
                buffering = it.phase != YPlaybackPhase.Ended,
                error = null,
                errorCategory = null,
            )
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
        mutableState.updateState {
            it.copy(
                positionMs = target,
                buffering = it.playbackRequested,
                phase = if (it.phase == YPlaybackPhase.Ended) YPlaybackPhase.Ready else it.phase,
            )
        }
        commands.trySend(Command.Seek(target * MICROS_PER_MILLISECOND))
    }

    override fun setSpeed(speed: Float) {
        if (released || !speed.isFinite() || speed <= 0f) return
        mutableState.updateState { it.copy(speed = speed) }
        commands.trySend(Command.SetSpeed(speed))
    }

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        if (released) return
        when (type) {
            YTrackType.Audio -> {
                val trackId = id.removePrefix(AUDIO_TRACK_PREFIX).toIntOrNull() ?: return
                if (mutableState.value.audioTracks.any { it.id == id && !it.selected }) {
                    commands.trySend(Command.SelectAudioTrack(trackId))
                }
            }
            YTrackType.Subtitle -> {
                val command =
                    when (id) {
                        SUBTITLE_OFF -> Command.SelectSubtitleTrack(null, external = false)
                        EXTERNAL_SUBTITLE_TRACK_ID -> Command.SelectSubtitleTrack(null, external = true)
                        else -> {
                            val trackId = id.removePrefix(SUBTITLE_TRACK_PREFIX).toIntOrNull() ?: return
                            Command.SelectSubtitleTrack(trackId, external = false)
                        }
                    }
                if (id == SUBTITLE_OFF || mutableState.value.subtitleTracks.any { it.id == id && !it.selected }) {
                    commands.trySend(command)
                }
            }
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

    override fun retry() {
        if (released) return
        mutableState.updateState {
            it.copy(
                phase = YPlaybackPhase.Preparing,
                error = null,
                errorCategory = null,
                buffering = it.playbackRequested,
            )
        }
        commands.trySend(Command.Prepare)
    }

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

    private suspend fun runLoop() {
        val session =
            AndroidEnhancedPlaybackSession(
                context = appContext,
                runtimeCapabilities = AndroidRuntimeCapabilityRegistry(appContext),
                frameRateSwitchMode = frameRateSwitchMode,
            )
        var surfaceOutput: AndroidSurfaceVideoOutput? = null
        var currentIndex = request.startIndex
        var requestedPlay = request.autoPlay
        var speed = 1f
        var prepared = false
        var lastPublishNs = 0L
        var externalSubtitle: AndroidLoadedExternalSubtitle? = null
        var externalSubtitleSelected = false
        var activePlan: YPlaybackPlan? = null
        var activeDolbyProfile: Int? = null

        suspend fun prepareCurrent(positionUs: Long) {
            val output = surfaceOutput?.surface?.takeIf { it.isValid }
            if (output == null) {
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
                    allowAudioPassthrough = allowAudioPassthrough,
                )
            val playbackPlan = forcedPlan ?: decision?.plan
            checkNotNull(playbackPlan) { "Media item has no executable YCore enhanced plan" }
            if (forcedPlan == null) {
                check(decision?.nativeEnhancedExecutable == true) {
                    "Media item is not eligible for YCore NativeEnhanced"
                }
            } else {
                check(playbackPlan.demuxPath == YDemuxPath.Enhanced) {
                    "Forced YCore plan must use the enhanced demux path"
                }
                check(playbackPlan.route in setOf(YPlaybackRoute.SoftwareFallback, YPlaybackRoute.GpuEnhanced)) {
                    "Only YCore software and measured GPU plans may be forced"
                }
            }
            val result =
                session.open(
                    source =
                        YDemuxSource(
                            uri = item.uri,
                            headers = item.headers,
                            cacheIdentity = item.cacheIdentity,
                            cacheMaximumBytes = item.cacheMaximumBytes,
                        ),
                    plan = playbackPlan,
                    surface = output,
                    startPositionUs = positionUs.coerceAtLeast(0L),
                    runtimeCapabilityKey = decision?.runtimeCapabilityKey(),
                )
            prepared = true
            activePlan = playbackPlan
            speed = mutableState.value.speed
            session.setSpeed(speed)
            val tracks = result.toAudioTracks()
            externalSubtitle =
                item.externalSubtitle?.let { source ->
                    runCatching { externalSubtitleLoader.load(source, item.headers) }.getOrNull()
                }
            externalSubtitleSelected = externalSubtitle != null
            val subtitleTracks =
                result.toSubtitleTracks() +
                    listOfNotNull(externalSubtitle?.track?.copy(selected = externalSubtitleSelected))
            val video = result.tracks.firstOrNull { it.type == YDemuxTrackType.Video }?.video
            activeDolbyProfile = video?.dolbyVisionConfig?.profile
            mutableState.updateState {
                it.copy(
                    phase = YPlaybackPhase.Ready,
                    playing = false,
                    buffering = requestedPlay,
                    durationMs = (result.durationUs ?: 0L) / MICROS_PER_MILLISECOND,
                    currentIndex = currentIndex,
                    itemCount = request.items.size,
                    audioTracks = tracks,
                    subtitleTracks = subtitleTracks,
                    subtitleCues = externalSubtitle?.cues.orEmpty(),
                    error = null,
                    errorCategory = null,
                    diagnostics =
                        it.diagnostics.copy(
                            route = playbackPlan.route,
                            container = result.container.name,
                            demuxer = "FFmpeg 8.1 / libavformat",
                            videoCodec = video?.mimeType.orEmpty(),
                            videoWidth = video?.width ?: 0,
                            videoHeight = video?.height ?: 0,
                            frameRate = video?.frameRate ?: 0f,
                            audioCodec =
                                result.tracks
                                    .firstOrNull { it.type == YDemuxTrackType.Audio }
                                    ?.audio
                                    ?.mimeType
                                    .orEmpty(),
                            bitrateBitsPerSecond = result.bitRateBitsPerSecond ?: 0L,
                            dynamicRange = video?.hdrType?.name.orEmpty(),
                            videoOutput = "等待首帧",
                            audioOutput = if (tracks.isEmpty()) "无音频轨" else "等待 PCM 输出",
                            reason = playbackPlan.reason,
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
            mutableState.updateState {
                it.copy(
                    phase = if (snapshot.ended) YPlaybackPhase.Ended else YPlaybackPhase.Ready,
                    playing = snapshot.playing,
                    buffering = snapshot.buffering,
                    playbackRequested = requestedPlay && !snapshot.ended,
                    positionMs = snapshot.positionUs / MICROS_PER_MILLISECOND,
                    subtitleCues =
                        if (externalSubtitleSelected) {
                            externalSubtitle?.cues.orEmpty()
                        } else {
                            snapshot.subtitleCues
                        },
                    diagnostics =
                        it.diagnostics.copy(
                            decoder =
                                listOfNotNull(snapshot.videoDecoderName, snapshot.audioDecoderName)
                                    .joinToString(" + "),
                            videoOutput =
                                when {
                                    snapshot.nativeGpuFeatureMask != 0L && snapshot.firstVideoFrameRendered ->
                                        nativeGpuOutputLabel(
                                            plan = activePlan,
                                            dolbyProfile = activeDolbyProfile,
                                            gpuFrameDurationNs = snapshot.gpuFrameDurationNs,
                                        )
                                    snapshot.nativeGpuFeatureMask != 0L -> "等待 Vulkan 实测门槛"
                                    snapshot.firstVideoFrameRendered -> "Surface 直出"
                                    else -> "等待首帧"
                                },
                            audioOutput =
                                if (snapshot.audioRendering) {
                                    when {
                                        snapshot.dolbyAtmosOutput -> "Dolby Atmos 原码 · AudioTrack"
                                        snapshot.audioPassthrough -> "原码直通 · AudioTrack"
                                        else -> "PCM · AudioTrack"
                                    }
                                } else {
                                    it.diagnostics.audioOutput
                                },
                            videoOutputVerified = snapshot.firstVideoFrameRendered,
                            audioOutputVerified = snapshot.audioRendering,
                            // Native DV output claim requires a verified video frame AND a DV source
                            // route. P7 FEL composition remains a separate evidence gate.
                            dolbyVisionOutput =
                                snapshot.firstVideoFrameRendered &&
                                    snapshot.outputHdrType == com.yfuse.core2.capability.YHdrType.DolbyVision,
                            dolbyAtmosOutput = snapshot.dolbyAtmosOutput,
                            audioUnderrunCount = snapshot.audioFallbackCount,
                            droppedFrames = snapshot.droppedFrames,
                            avSyncOffsetMs = snapshot.avSyncOffsetUs?.div(MICROS_PER_MILLISECOND),
                            avSyncMeasurement =
                                if (snapshot.avSyncOffsetUs != null) {
                                    "MediaCodec 帧渲染 / AudioTrack 时钟"
                                } else {
                                    "等待音视频时钟样本"
                                },
                        ),
                )
            }
        }

        try {
            while (scope.isActive) {
                val pendingCommands = mutableListOf<Command>()
                while (true) {
                    val command = commands.tryReceive().getOrNull() ?: break
                    pendingCommands += command
                }
                val handled = pendingCommands.isNotEmpty()
                coalesceNativeEnhancedCommands(pendingCommands).forEach { command ->
                    try {
                        when (command) {
                            Command.Prepare ->
                                prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                            Command.Play -> {
                                requestedPlay = true
                                if (!prepared) {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                } else {
                                    session.play()
                                }
                            }
                            Command.Pause -> {
                                requestedPlay = false
                                if (prepared) session.pause()
                            }
                            is Command.Seek -> {
                                if (prepared) session.seekTo(command.positionUs)
                            }
                            is Command.SetSpeed -> {
                                speed = command.speed
                                if (prepared) session.setSpeed(speed)
                            }
                            is Command.SetVideoOutput -> {
                                val previous = surfaceOutput
                                surfaceOutput = command.output
                                val next = command.output?.surface?.takeIf { it.isValid }
                                if (next == null) {
                                    val position =
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
                                            positionMs = position / MICROS_PER_MILLISECOND,
                                            diagnostics = it.diagnostics.copy(videoOutput = "等待 Surface"),
                                        )
                                    }
                                } else if (prepared && previous?.surface?.isValid == true) {
                                    runCatching { session.setOutputSurface(next) }
                                        .onFailure {
                                            val position = session.snapshot().positionUs
                                            session.close()
                                            prepared = false
                                            prepareCurrent(position)
                                        }
                                } else if (!prepared) {
                                    prepareCurrent(mutableState.value.positionMs * MICROS_PER_MILLISECOND)
                                }
                            }
                            is Command.SelectAudioTrack -> {
                                if (prepared) {
                                    session.selectAudioTrack(
                                        YTrackId(command.trackId),
                                        capabilityProvider.current(),
                                    )
                                    val selectedTrackId = "$AUDIO_TRACK_PREFIX${command.trackId}"
                                    mutableState.updateState { state ->
                                        state.copy(
                                            audioTracks =
                                                state.audioTracks.map { track ->
                                                    track.copy(selected = track.id == selectedTrackId)
                                                },
                                            error = null,
                                            errorCategory = null,
                                        )
                                    }
                                }
                            }
                            is Command.SelectSubtitleTrack -> {
                                if (prepared) {
                                    if (!command.external || externalSubtitle != null) {
                                        session.selectSubtitleTrack(command.trackId?.let(::YTrackId))
                                        externalSubtitleSelected = command.external
                                    }
                                    val selectedTrackId =
                                        when {
                                            externalSubtitleSelected -> EXTERNAL_SUBTITLE_TRACK_ID
                                            command.trackId != null -> "$SUBTITLE_TRACK_PREFIX${command.trackId}"
                                            else -> null
                                        }
                                    mutableState.updateState { state ->
                                        state.copy(
                                            subtitleTracks =
                                                state.subtitleTracks.map { track ->
                                                    track.copy(selected = track.id == selectedTrackId)
                                                },
                                            subtitleCues =
                                                if (externalSubtitleSelected) {
                                                    externalSubtitle?.cues.orEmpty()
                                                } else {
                                                    emptyList()
                                                },
                                            error = null,
                                            errorCategory = null,
                                        )
                                    }
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
                        val typed = failure as? YPlaybackException
                        session.close()
                        prepared = false
                        requestedPlay = false
                        mutableState.updateState {
                            it.copy(
                                phase = YPlaybackPhase.Failed,
                                playing = false,
                                playbackRequested = false,
                                buffering = false,
                                error = "YCore 2.0 增强播放路径失败，已安全停止",
                                // Unknown is deliberately non-penalizing until each native stage has
                                // a typed failure domain. Never infer decoder failure from text.
                                errorCategory = typed?.category ?: YPlaybackFailureCategory.Unknown,
                                diagnostics =
                                    it.diagnostics.copy(
                                        videoOutput = "停止",
                                        audioOutput = "停止",
                                        reason =
                                            typed?.stage?.let { stage -> "NativeEnhanced failed at ${stage.name}" }
                                                ?: "NativeEnhanced failed before typed-stage classification",
                                    ),
                            )
                        }
                    }
                }

                val didWork = if (prepared && requestedPlay) session.pump() else false
                publishSnapshot()
                if (!handled && !didWork) delay(PUMP_IDLE_DELAY_MS)
            }
        } finally {
            session.close()
        }
    }

    internal sealed interface Command {
        data object Prepare : Command

        data object Play : Command

        data object Pause : Command

        data class Seek(
            val positionUs: Long,
        ) : Command

        data class SetSpeed(
            val speed: Float,
        ) : Command

        data class SetVideoOutput(
            val output: AndroidSurfaceVideoOutput?,
        ) : Command

        data class SelectAudioTrack(
            val trackId: Int,
        ) : Command

        data class SelectSubtitleTrack(
            val trackId: Int?,
            val external: Boolean,
        ) : Command

        data class SelectItem(
            val index: Int,
        ) : Command
    }
}

internal fun coalesceNativeEnhancedCommands(
    commands: List<AndroidNativeEnhancedYPlayer.Command>,
): List<AndroidNativeEnhancedYPlayer.Command> =
    commands.fold(mutableListOf()) { result, command ->
        val previous = result.lastOrNull()
        if (previous != null && previous.canBeReplacedBy(command)) {
            result[result.lastIndex] = command
        } else {
            result += command
        }
        result
    }

private fun AndroidNativeEnhancedYPlayer.Command.canBeReplacedBy(next: AndroidNativeEnhancedYPlayer.Command): Boolean =
    when (this) {
        is AndroidNativeEnhancedYPlayer.Command.Seek -> next is AndroidNativeEnhancedYPlayer.Command.Seek
        is AndroidNativeEnhancedYPlayer.Command.SetSpeed -> next is AndroidNativeEnhancedYPlayer.Command.SetSpeed
        is AndroidNativeEnhancedYPlayer.Command.SetVideoOutput ->
            next is AndroidNativeEnhancedYPlayer.Command.SetVideoOutput
        is AndroidNativeEnhancedYPlayer.Command.SelectAudioTrack ->
            next is AndroidNativeEnhancedYPlayer.Command.SelectAudioTrack
        is AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack ->
            next is AndroidNativeEnhancedYPlayer.Command.SelectSubtitleTrack
        is AndroidNativeEnhancedYPlayer.Command.SelectItem -> next is AndroidNativeEnhancedYPlayer.Command.SelectItem
        AndroidNativeEnhancedYPlayer.Command.Prepare -> next == AndroidNativeEnhancedYPlayer.Command.Prepare
        AndroidNativeEnhancedYPlayer.Command.Play -> next == AndroidNativeEnhancedYPlayer.Command.Play
        AndroidNativeEnhancedYPlayer.Command.Pause -> next == AndroidNativeEnhancedYPlayer.Command.Pause
    }

private fun YDemuxOpenResult.toAudioTracks(): List<YTrack> {
    val firstAudioId = tracks.firstOrNull { it.type == YDemuxTrackType.Audio }?.id
    return tracks.mapNotNull { track ->
        val audio = track.audio ?: return@mapNotNull null
        YTrack(
            id = "audio:${track.id.value}",
            type = YTrackType.Audio,
            label = track.label ?: track.language ?: "Audio ${track.id.value + 1}",
            language = track.language,
            codec = audio.mimeType,
            selected = track.id == firstAudioId,
        )
    }
}

private fun YDemuxOpenResult.toSubtitleTracks(): List<YTrack> =
    tracks.mapNotNull { track ->
        val subtitle =
            track.subtitle?.takeIf {
                it.format.textOverlaySupported ||
                    it.format == com.yfuse.core2.subtitle.YSubtitleFormat.Pgs ||
                    it.format == com.yfuse.core2.subtitle.YSubtitleFormat.VobSub
            } ?: return@mapNotNull null
        YTrack(
            id = "$SUBTITLE_TRACK_PREFIX${track.id.value}",
            type = YTrackType.Subtitle,
            label = track.label ?: track.language ?: "Subtitle ${track.id.value + 1}",
            language = track.language,
            codec = subtitle.mimeType,
            selected = false,
        )
    }

private inline fun MutableStateFlow<YPlayerState>.updateState(transform: (YPlayerState) -> YPlayerState) {
    value = transform(value)
}

private fun nativeGpuOutputLabel(
    plan: YPlaybackPlan?,
    dolbyProfile: Int?,
    gpuFrameDurationNs: Long,
): String {
    val duration = gpuFrameDurationNs / 1_000_000.0
    return when {
        plan?.usesHdrFallback == true ->
            "DV P${dolbyProfile ?: "7/8"} 兼容基层 ${plan.inputHdrType} → ${plan.outputHdrType} · " +
                "Vulkan ${duration} ms"
        plan?.inputHdrType == YHdrType.DolbyVision ->
            "DV P${dolbyProfile ?: "5/8"} MediaCodec → Vulkan（非原生 DV 输出）· ${duration} ms"
        plan != null && plan.inputHdrType != plan.outputHdrType ->
            "${plan.inputHdrType} → ${plan.outputHdrType} · Vulkan ${duration} ms"
        else -> "Vulkan Swapchain · GPU ${duration} ms"
    }
}

private const val MICROS_PER_MILLISECOND = 1_000L
private const val AUDIO_TRACK_PREFIX = "audio:"
private const val SUBTITLE_TRACK_PREFIX = "subtitle:"
private const val SUBTITLE_OFF = "off"
private const val STATE_PUBLISH_INTERVAL_NS = 200_000_000L
private const val PUMP_IDLE_DELAY_MS = 2L
