package com.yfuse.feature.player

import androidx.compose.runtime.Immutable

/** Only fields consumed by the bottom timeline; diagnostic and track churn stay outside it. */
@Immutable
internal data class PlaybackTransportState(
    val currentIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
    val speed: Float,
    val buttons: PlaybackButtonState,
)

/** Transport buttons have no position or buffer subscription. */
@Immutable
internal data class PlaybackButtonState(
    val playing: Boolean,
    val buffering: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val seekable: Boolean,
)

internal fun PlaybackState.transportState(): PlaybackTransportState =
    PlaybackTransportState(
        currentIndex = currentIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        speed = speed,
        buttons = PlaybackButtonState(playing, buffering, hasPrevious, hasNext, durationMs > 0L),
    )
