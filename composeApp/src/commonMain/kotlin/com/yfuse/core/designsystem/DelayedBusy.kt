package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Whether a wait has earned its indicator.
 *
 * True once [active] has lasted [showAfterMillis]; then held for at least [minVisibleMillis]
 * even if the work finishes sooner. A cache hit therefore shows nothing at all, and a slow
 * request shows an indicator that stays long enough to be read instead of blinking.
 */
@Composable
fun rememberDelayedBusy(
    active: Boolean,
    showAfterMillis: Int = Motion.BUSY_SHOW_AFTER,
    minVisibleMillis: Int = Motion.BUSY_MIN_VISIBLE,
): Boolean {
    var shown by remember { mutableStateOf(false) }
    var shownAt by remember { mutableStateOf<TimeMark?>(null) }
    LaunchedEffect(active) {
        if (active) {
            if (!shown) {
                delay(showAfterMillis.toLong())
                shown = true
                shownAt = TimeSource.Monotonic.markNow()
            }
        } else if (shown) {
            val elapsed = shownAt?.elapsedNow()?.inWholeMilliseconds ?: minVisibleMillis.toLong()
            val remaining = minVisibleMillis - elapsed
            if (remaining > 0) delay(remaining)
            shown = false
            shownAt = null
        }
    }
    return shown
}
