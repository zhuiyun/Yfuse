package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.EngineTrack
import com.yfuse.feature.player.PlaybackOutputReadiness
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.VideoEngine
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * Compatibility bridge that keeps the current Exo/mpv/MDK stack alive while the App moves to
 * [YPlayer]. New UI/control code can target the product API first; Core2 can then replace the
 * implementation without another screen-level migration.
 *
 * The adapter deliberately owns no coroutine scope. Its state is a live mapped view over the
 * engine's StateFlow, so constructing or replacing an adapter cannot leak a collector during an
 * engine handover.
 */
internal class LegacyYPlayerAdapter(
    private val engine: VideoEngine,
) : YPlayer {
    /** Full product presentation state retained until YPlayerState covers backend extensions. */
    internal val presentationState: StateFlow<PlaybackState> get() = engine.state

    override val state: StateFlow<YPlayerState> =
        MappedStateFlow(engine.state) { state ->
            state.toYPlayerState(engine.playbackRequested)
        }

    override val playbackRequested: Boolean get() = engine.playbackRequested

    override fun play() = engine.play()

    override fun pause() = engine.pause()

    override fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    override fun setSpeed(speed: Float) = engine.setSpeed(speed)

    override fun selectTrack(
        type: YTrackType,
        id: String,
    ) {
        when (type) {
            YTrackType.Audio -> engine.selectAudioTrack(id)
            YTrackType.Subtitle -> engine.selectSubtitleTrack(id)
        }
    }

    override fun selectItem(index: Int) = engine.selectItem(index)

    override fun selectDiscTitle(index: Int): Boolean = engine.selectDiscTitle(index)

    override fun selectDiscChapter(index: Int): Boolean = engine.selectDiscChapter(index)

    override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean = engine.sendDiscMenuCommand(command)

    override fun currentPositionMs(): Long = engine.currentPositionMs()

    override fun retry() = engine.retry()

    override fun release() = engine.release()
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class MappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target get() = transform(source.value)

    override val replayCache: List<Target> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Target>): Nothing =
        source.collect { value -> collector.emit(transform(value)) }
}

private fun PlaybackState.toYPlayerState(playbackRequested: Boolean): YPlayerState =
    YPlayerState(
        phase =
            when {
                error != null -> YPlaybackPhase.Failed
                ended -> YPlaybackPhase.Ended
                buffering -> YPlaybackPhase.Preparing
                else -> YPlaybackPhase.Ready
            },
        playing = playing,
        playbackRequested = playbackRequested,
        buffering = buffering,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        speed = speed,
        currentIndex = currentIndex,
        itemCount = itemCount,
        audioTracks = audioTracks.map { it.toYTrack(YTrackType.Audio) },
        subtitleTracks = subtitleTracks.map { it.toYTrack(YTrackType.Subtitle) },
        discNavigation = discNavigation,
        error = error,
        errorCategory = errorKind?.toYPlaybackFailureCategory(),
        diagnostics =
            YPlayerDiagnostics(
                route = YPlaybackRoute.Legacy,
                demuxer = diagnostics.mediaProbe,
                decoder = diagnostics.decoder,
                renderer = diagnostics.plannedRenderPath,
                dynamicRange = diagnostics.dynamicRange,
                videoOutput = diagnostics.videoOutput,
                audioOutput = diagnostics.audioOutput,
                videoOutputVerified = diagnostics.videoReadiness == PlaybackOutputReadiness.Rendering,
                audioOutputVerified = diagnostics.audioReadiness == PlaybackOutputReadiness.Rendering,
                dolbyVisionOutput = diagnostics.dolbyVisionOutput,
                dolbyAtmosOutput = diagnostics.dolbyAtmosOutput,
                avSyncOffsetMs = diagnostics.avSyncOffsetMs,
                avSyncMeasurement = diagnostics.avSyncMeasurement,
                reason = diagnostics.planningReason ?: diagnostics.fallbackReason,
            ),
    )

private fun PlaybackFailureKind.toYPlaybackFailureCategory(): YPlaybackFailureCategory =
    when (this) {
        PlaybackFailureKind.Authorization -> YPlaybackFailureCategory.Authorization
        PlaybackFailureKind.Drm -> YPlaybackFailureCategory.Drm
        PlaybackFailureKind.Network -> YPlaybackFailureCategory.Network
        PlaybackFailureKind.Container -> YPlaybackFailureCategory.Container
        PlaybackFailureKind.Decoder -> YPlaybackFailureCategory.Decoder
        PlaybackFailureKind.Renderer -> YPlaybackFailureCategory.Renderer
        PlaybackFailureKind.AudioSink -> YPlaybackFailureCategory.AudioSink
        PlaybackFailureKind.Unknown -> YPlaybackFailureCategory.Unknown
    }

private fun EngineTrack.toYTrack(type: YTrackType): YTrack =
    YTrack(
        id = id,
        type = type,
        label = label,
        language = language,
        codec = codec,
        selected = selected,
    )
