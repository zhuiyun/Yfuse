package com.yfuse.core.designsystem

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Plays through the host [View], which is what routes the request past the system's own
 * "touch feedback" setting — nothing here has to check it, and nothing here should
 * override it.
 *
 * The richer constants ([HapticFeedbackConstants.CONFIRM] and friends) arrived in API 30;
 * this app's `minSdk` is 26, so each one names the closest older constant to fall back to.
 * They are compile-time `int`s, so the reference itself is safe on any version — only the
 * device's willingness to render it is not.
 */
private class ViewHaptics(private val view: View) : Haptics {
    override fun play(signal: HapticSignal) {
        val constant = when (signal) {
            HapticSignal.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
            HapticSignal.Select -> HapticFeedbackConstants.CLOCK_TICK
            HapticSignal.Confirm -> apiThirtyOr(
                HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.VIRTUAL_KEY,
            )
            HapticSignal.Reject -> apiThirtyOr(
                HapticFeedbackConstants.REJECT,
                HapticFeedbackConstants.LONG_PRESS,
            )
            HapticSignal.Threshold -> apiThirtyOr(
                HapticFeedbackConstants.GESTURE_START,
                HapticFeedbackConstants.CLOCK_TICK,
            )
        }
        view.performHapticFeedback(constant)
    }

    private fun apiThirtyOr(preferred: Int, fallback: Int): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) preferred else fallback
}

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}
