package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayerState

/** A child demuxer sees one Period; the public player and recovery commands see the whole title. */
internal fun mapAdaptivePresentationState(
    state: YPlayerState,
    target: YAdaptivePlaybackTarget?,
): YPlayerState {
    if (target == null) return state
    val duration =
        target.presentationDurationMs.takeIf { it > 0L }
            ?: state.durationMs + target.presentationOffsetMs

    fun global(local: Long): Long =
        (local + target.presentationOffsetMs).let {
            if (duration > 0L) it.coerceAtMost(duration) else it
        }
    return state.copy(
        positionMs = global(state.positionMs),
        bufferedPositionMs = global(state.bufferedPositionMs),
        durationMs = duration,
    )
}
