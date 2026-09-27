package com.yfuse.core.designsystem

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * The platform constant for [signal] on a device running API [sdk], or null where that device
 * has nothing fitting and saying nothing is better than saying the wrong thing.
 *
 * The richer constants ([HapticFeedbackConstants.CONFIRM] and friends) arrived in API 30 and the
 * segment and gesture-threshold ones in API 34; this app's `minSdk` is 26, so each names the
 * closest older constant to fall back to. They are compile-time `int`s, so the reference itself
 * is safe on any version — only the device's willingness to render it is not. Kept apart from
 * the [View] so the table can be checked version by version.
 */
internal fun hapticFeedbackConstant(
    signal: HapticSignal,
    sdk: Int,
): Int? {
    val u = sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val r = sdk >= Build.VERSION_CODES.R
    return when (signal) {
        HapticSignal.Tap -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticSignal.Select -> HapticFeedbackConstants.CLOCK_TICK
        HapticSignal.LongPress -> HapticFeedbackConstants.LONG_PRESS
        HapticSignal.Confirm -> if (r) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        HapticSignal.Reject -> if (r) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        // The constant made for exactly this — a gesture crossing its commit point.
        HapticSignal.Threshold ->
            when {
                u -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                r -> HapticFeedbackConstants.GESTURE_START
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
        // Falling back below the line is quieter than crossing it; before API 30 there is
        // nothing quiet enough, and a second full tick would read as a second commit.
        HapticSignal.ThresholdRelease ->
            when {
                u -> HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                r -> HapticFeedbackConstants.GESTURE_END
                else -> null
            }
        // Made for a drag stepping across segments; lighter than a selection.
        HapticSignal.Tick -> if (u) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        HapticSignal.FrequentTick ->
            when {
                u -> HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                sdk >= Build.VERSION_CODES.O_MR1 -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
        HapticSignal.DragStart -> if (u) HapticFeedbackConstants.DRAG_START else HapticFeedbackConstants.LONG_PRESS
        HapticSignal.ToggleOn -> if (u) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
        HapticSignal.ToggleOff -> if (u) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
    }
}

/**
 * Plays through the host [View], which is what routes the request past the system's own
 * "touch feedback" setting — nothing here has to check it, and nothing here should
 * override it.
 */
private class ViewHaptics(
    private val view: View,
) : Haptics {
    /** When the last repeating mark played, on the uptime clock; see [hapticAllowed]. */
    private var lastRepeatAt = Long.MIN_VALUE / 2

    override fun play(signal: HapticSignal) {
        val now = SystemClock.uptimeMillis()
        if (!hapticAllowed(signal, now - lastRepeatAt)) return
        val constant = hapticFeedbackConstant(signal, Build.VERSION.SDK_INT) ?: return
        if (signal.repeats()) lastRepeatAt = now
        view.performHapticFeedback(constant)
    }
}

@Composable
actual fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { ViewHaptics(view) }
}
