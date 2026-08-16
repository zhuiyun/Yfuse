package com.yfuse.feature.player

/** Engine-neutral state that must survive a backend or media-source rebuild. */
internal data class PlaybackHandoverSnapshot(
    val itemIndex: Int,
    val positionMs: Long,
    val playbackRequested: Boolean,
    val speed: Float,
)

/** Captures user intent instead of inferring it from buffering/rendering state. */
internal fun playbackHandoverSnapshot(
    state: PlaybackState,
    currentPositionMs: Long,
    playbackRequested: Boolean,
    requestedSpeed: Float,
): PlaybackHandoverSnapshot =
    PlaybackHandoverSnapshot(
        itemIndex = state.currentIndex.coerceAtLeast(0),
        positionMs = currentPositionMs.coerceAtLeast(0L),
        playbackRequested = playbackRequested && !state.ended,
        speed = requestedSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f,
    )
