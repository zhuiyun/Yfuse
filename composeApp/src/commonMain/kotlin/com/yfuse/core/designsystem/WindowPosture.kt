package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Physical hinge/fold posture reported by Jetpack WindowManager on foldables; [Flat] on rigid
 * screens and in unit tests.
 */
@Immutable
data class WindowPosture(
    /** Half-opened with a horizontal hinge: upper half faces the viewer, lower half rests flat. */
    val isTabletop: Boolean = false,
    /** Vertical hinge splitting the window into left and right halves. */
    val isBookMode: Boolean = false,
    /** Vertical hinge center as a fraction of window width (`0.30f..0.70f`), when present. */
    val verticalHingeFraction: Float? = null,
    /** Horizontal hinge center as a fraction of window height (`0.30f..0.70f`), when present. */
    val horizontalHingeFraction: Float? = null,
    /** Physical hinge gap to keep content clear of the crease. */
    val hingeThicknessDp: Dp = 0.dp,
) {
    companion object {
        val Flat = WindowPosture()
    }
}

val LocalWindowPosture = staticCompositionLocalOf { WindowPosture.Flat }
