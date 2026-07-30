package com.yfuse.feature.player

import kotlinx.coroutines.flow.StateFlow

/** A selectable audio/subtitle/video track exposed by an engine. */
data class EngineTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
) {
    companion object {
        /** Passed to [VideoEngine.selectSubtitleTrack] to turn subtitles off. */
        const val OFF = "off"
    }
}

data class PlaybackDiagnostics(
    val engine: String = "",
    val decoder: String = "等待视频轨道",
    val videoCodec: String = "未知",
    val playMethod: String = "直播放",
    val bitrateBitsPerSecond: Long = 0L,
    val frameRate: Float = 0f,
    val droppedFrames: Int = 0,
    val bufferedDurationMs: Long = 0L,
    val bufferEvents: Int = 0,
    val networkBitsPerSecond: Long = 0L,
)

/** Everything the glass control layer needs to render, engine-agnostic. */
data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    /** Decoded picture height, for the "1080P" badge; 0 until the first frame. */
    val videoHeight: Int = 0,
    val currentIndex: Int = 0,
    val itemCount: Int = 1,
    val audioTracks: List<EngineTrack> = emptyList(),
    val subtitleTracks: List<EngineTrack> = emptyList(),
    val error: String? = null,
    /** True after the current item reaches its natural end. */
    val ended: Boolean = false,
    val diagnostics: PlaybackDiagnostics = PlaybackDiagnostics(),
) {
    val hasNext: Boolean get() = currentIndex + 1 < itemCount
    val hasPrevious: Boolean get() = currentIndex > 0

    /** Milliseconds left in the current entry; 0 while the duration is unknown. */
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0L)
}

/**
 * Playback backend contract. ExoPlayer and libmpv each implement it so the
 * control layer — progress, play/pause, seek, speed, track pickers, episode
 * navigation — is written once and works with whichever engine is active.
 */
interface VideoEngine {
    val state: StateFlow<PlaybackState>

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(id: String)

    /** [EngineTrack.OFF] disables subtitles. */
    fun selectSubtitleTrack(id: String)

    /** Jumps to another entry in the queue — next/previous and the episode list. */
    fun selectItem(index: Int)

    /** Current position, for handing over when switching engines. */
    fun currentPositionMs(): Long

    /** Clears a recoverable playback error and retries the current entry. */
    fun retry()

    fun release()
}
