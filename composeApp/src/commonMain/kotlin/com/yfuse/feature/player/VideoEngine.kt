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
    /**
     * True while the current entry is coming from the server's transcoded stream rather
     * than its original file. Lives here, rather than on one engine, because every engine
     * can end up transcoding and the control layer shows the same badge either way.
     */
    val transcoding: Boolean = false,
    /** True once nothing further is left to fall back to for the current entry. */
    val fallbacksExhausted: Boolean = false,
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

    /**
     * Whether this engine has been asked to play, even if it is not rendering yet.
     *
     * [PlaybackState.playing] deliberately describes actual playback and therefore becomes
     * false while an engine is preparing or buffering. A watch-together room needs the
     * requested state instead: buffering on the host must not be broadcast as a pause.
     * Engines that can expose that distinction override this property.
     */
    val playbackRequested: Boolean get() = state.value.playing

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

    /**
     * Reloads the current entry from the server's transcoded stream, returning false when
     * there is nothing left to fall back to — no transcode URL, or one already in use.
     *
     * Every engine implements this rather than only ExoPlayer. A file the device cannot
     * decode is the single most common way playback fails, and on the native engines it
     * used to be a dead end: no automatic retry and, because the manual 转码播放 control was
     * gated on the engine being ExoPlayer, no way to ask for one either.
     */
    fun switchToTranscode(): Boolean = false

    /**
     * Adds entries to the end of the queue without disturbing what is playing.
     *
     * Returns false when this engine cannot, leaving the caller to rebuild it — which
     * restarts the current entry at its current position. A series queue is re-listed from
     * the server every couple of minutes while it plays, so a show that publishes an episode
     * mid-episode used to interrupt the episode being watched to make room for it.
     */
    fun appendItems(items: List<PlayerMediaItem>): Boolean = false

    fun release()
}
