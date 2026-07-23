package com.yfuse.feature.player

import kotlinx.coroutines.flow.StateFlow

/** A selectable audio/subtitle/video track exposed by an engine. */
data class EngineTrack(
    val id: String,
    val label: String,
    val language: String?,
    val selected: Boolean,
)

/** Everything the glass control layer needs to render, engine-agnostic. */
data class PlaybackState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val audioTracks: List<EngineTrack> = emptyList(),
    val subtitleTracks: List<EngineTrack> = emptyList(),
    val error: String? = null,
)

/**
 * Playback backend contract. ExoPlayer and libmpv each implement it so the
 * control layer — progress, play/pause, seek, speed, track pickers — is written
 * once and works with whichever engine is active.
 */
interface VideoEngine {
    val state: StateFlow<PlaybackState>

    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    fun selectAudioTrack(id: String)
    fun selectSubtitleTrack(id: String)

    /** Current position, for handing over when switching engines. */
    fun currentPositionMs(): Long

    fun release()
}
