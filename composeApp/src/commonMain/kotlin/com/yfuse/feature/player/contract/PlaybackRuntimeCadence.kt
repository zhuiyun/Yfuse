package com.yfuse.feature.player

/**
 * Selects a low-overhead polling cadence without making individual engines own power policy.
 *
 * Active playback remains responsive, while a paused or completed engine backs off instead of
 * waking the CPU several times per second to publish an unchanged state.
 */
internal data class PlaybackRuntimeCadence(
    val activeIntervalMs: Long,
    val idleIntervalMs: Long,
) {
    init {
        require(activeIntervalMs > 0L)
        require(idleIntervalMs >= activeIntervalMs)
    }

    fun intervalMs(
        playing: Boolean,
        buffering: Boolean,
        pendingWork: Boolean = false,
    ): Long =
        if (playing || buffering || pendingWork) {
            activeIntervalMs
        } else {
            idleIntervalMs
        }
}

/** UI progress only needs two updates per second; native per-frame callbacks are redundant. */
internal const val PLAYBACK_PROGRESS_STEP_MS = 500L
