package com.yfuse.feature.player

import androidx.compose.ui.unit.IntSize
import com.yfuse.core.designsystem.AMBIENT_LIGHT_SAMPLE_MS

/** Survives effect restarts, so seeking cannot bypass the minimum request interval. */
internal class AmbientSamplingPolicy {
    private var lastRequestMs: Long? = null
    private var unchangedSamples = 0
    private var failures = 0
    var intervalMs: Long = AMBIENT_LIGHT_SAMPLE_MS
        private set

    fun waitMs(
        nowMs: Long,
        urgent: Boolean = false,
    ): Long {
        val last = lastRequestMs ?: return 0L
        val interval = if (urgent) AMBIENT_LIGHT_SAMPLE_MS else intervalMs
        return (last + interval - nowMs).coerceAtLeast(0L)
    }

    fun started(nowMs: Long) {
        lastRequestMs = nowMs
    }

    fun succeeded(changed: Boolean) {
        failures = 0
        unchangedSamples = if (changed) 0 else (unchangedSamples + 1).coerceAtMost(10)
        intervalMs =
            when {
                unchangedSamples >= 10 -> 2_000L
                unchangedSamples >= 4 -> 1_000L
                else -> AMBIENT_LIGHT_SAMPLE_MS
            }
    }

    fun failed(): Boolean {
        unchangedSamples = 0
        failures = (failures + 1).coerceAtMost(6)
        intervalMs =
            when (failures) {
                1, 2 -> AMBIENT_LIGHT_SAMPLE_MS
                3 -> 2_000L
                4 -> 5_000L
                5 -> 10_000L
                else -> 30_000L
            }
        return failures >= 3
    }

    fun reset() {
        unchangedSamples = 0
        failures = 0
        intervalMs = AMBIENT_LIGHT_SAMPLE_MS
        // Keep lastRequestMs: a new source still must not overlap or flood the copy lane.
    }
}

internal fun ambientLightHasVisibleBars(
    container: IntSize,
    picture: IntSize,
    guardPx: Int,
): Boolean =
    container.width > 0 &&
        container.height > 0 &&
        picture.width > 0 &&
        picture.height > 0 &&
        ((container.width - picture.width) / 2 > guardPx || (container.height - picture.height) / 2 > guardPx)
