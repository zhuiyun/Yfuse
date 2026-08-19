package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrack
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.EngineTrack
import com.yfuse.feature.player.PlaybackDiagnostics
import com.yfuse.feature.player.PlaybackOutputReadiness
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.VideoEngine
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * Temporary reverse bridge used while PlayerRoot presentation still speaks [VideoEngine].
 *
 * New Core2 players can be inserted behind today's UI without pretending to be Exo/mpv/MDK. The
 * adapter owns no playback policy and no renderer; it only translates the stable product contract
 * into the legacy presentation contract. Remove it after the remaining UI and backend-specific
 * queue surfaces consume [YPlayer] directly.
 */
internal class YPlayerVideoEngineAdapter(
    val player: YPlayer,
) : VideoEngine {
    override val state: StateFlow<PlaybackState> = player.asPlaybackStateFlow()

    override val playbackRequested: Boolean get() = player.playbackRequested

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    override fun setSpeed(speed: Float) = player.setSpeed(speed)

    override fun selectAudioTrack(id: String) = player.selectTrack(YTrackType.Audio, id)

    override fun selectSubtitleTrack(id: String) = player.selectTrack(YTrackType.Subtitle, id)

    override fun selectItem(index: Int) = player.selectItem(index)

    override fun currentPositionMs(): Long = player.currentPositionMs()

    override fun retry() = player.retry()

    override fun release() = player.release()
}

/** Returns the product player without wrapping a Core2 player back through the Legacy contract. */
internal fun VideoEngine.asYPlayer(): YPlayer =
    if (this is YPlayerVideoEngineAdapter) player else LegacyYPlayerAdapter(this)

/**
 * Product presentation bridge used while YPlayerState intentionally omits Legacy-only extensions.
 * Legacy keeps its full state object; native Core2 state is translated once at the UI boundary.
 */
internal fun YPlayer.asPlaybackStateFlow(): StateFlow<PlaybackState> =
    if (this is LegacyYPlayerAdapter) {
        presentationState
    } else {
        ReverseMappedStateFlow(state, YPlayerState::toLegacyPlaybackState)
    }

private class ReverseMappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target get() = transform(source.value)

    override val replayCache: List<Target> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Target>): Nothing =
        source.collect { value -> collector.emit(transform(value)) }
}

private fun YPlayerState.toLegacyPlaybackState(): PlaybackState =
    PlaybackState(
        playing = playing,
        buffering = buffering,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        speed = speed,
        currentIndex = currentIndex,
        itemCount = itemCount,
        audioTracks = audioTracks.map(YTrack::toEngineTrack),
        subtitleTracks = subtitleTracks.map(YTrack::toEngineTrack),
        error = error,
        errorKind = errorCategory?.toLegacyFailureKind(),
        ended = phase == YPlaybackPhase.Ended,
        fallbacksExhausted = phase == YPlaybackPhase.Failed,
        automaticFallbackBlocked =
            errorCategory == YPlaybackFailureCategory.Authorization ||
                errorCategory == YPlaybackFailureCategory.Drm,
        diagnostics =
            PlaybackDiagnostics(
                engine = "YCore 2.0",
                decoder = diagnostics.decoder.ifBlank { "等待解码器" },
                playMethod = diagnostics.route.name,
                dynamicRange = diagnostics.dynamicRange,
                videoOutput = diagnostics.videoOutput.ifBlank { "等待首帧" },
                audioOutput = diagnostics.audioOutput.ifBlank { "等待音频输出" },
                videoReadiness =
                    when {
                        diagnostics.videoOutputVerified -> PlaybackOutputReadiness.Rendering
                        phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
                        else -> PlaybackOutputReadiness.Waiting
                    },
                audioReadiness =
                    when {
                        diagnostics.audioOutputVerified -> PlaybackOutputReadiness.Rendering
                        phase == YPlaybackPhase.Idle -> PlaybackOutputReadiness.Released
                        else -> PlaybackOutputReadiness.Waiting
                    },
                dolbyVisionOutput = diagnostics.dolbyVisionOutput,
                dolbyAtmosOutput = diagnostics.dolbyAtmosOutput,
                plannedRenderPath = diagnostics.renderer,
                planningReason = diagnostics.reason,
                mediaProbe = diagnostics.demuxer,
            ),
    )

private fun YTrack.toEngineTrack(): EngineTrack =
    EngineTrack(
        id = id,
        label = label,
        language = language,
        selected = selected,
        codec = codec,
    )

private fun YPlaybackFailureCategory.toLegacyFailureKind(): PlaybackFailureKind =
    when (this) {
        YPlaybackFailureCategory.Authorization -> PlaybackFailureKind.Authorization
        YPlaybackFailureCategory.Drm -> PlaybackFailureKind.Drm
        YPlaybackFailureCategory.Network -> PlaybackFailureKind.Network
        YPlaybackFailureCategory.Container -> PlaybackFailureKind.Container
        YPlaybackFailureCategory.Decoder -> PlaybackFailureKind.Decoder
        YPlaybackFailureCategory.Renderer -> PlaybackFailureKind.Renderer
        YPlaybackFailureCategory.AudioSink -> PlaybackFailureKind.AudioSink
        YPlaybackFailureCategory.Unknown -> PlaybackFailureKind.Unknown
    }
