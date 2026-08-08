package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a touch is being told, rather than which waveform to play.
 *
 * Compose's common [androidx.compose.ui.hapticfeedback.HapticFeedbackType] offers only
 * `LongPress` and `TextHandleMove`, which is why the whole app had exactly two haptic
 * calls, both in the player, both `LongPress` standing in for something else. Naming the
 * intent lets each platform pick its own closest constant and lets call sites say what
 * happened instead of how it should buzz.
 */
enum class HapticSignal {
    /** A control was pressed and did the ordinary thing. */
    Tap,

    /** A selection moved — a tab, a chip, a row in a picker. */
    Select,

    /** A state the user asked for took effect: favourited, marked watched, sent. */
    Confirm,

    /** The action could not be taken: locked by the room host, nothing to play. */
    Reject,

    /** A drag crossed the point where releasing would commit — pull-to-refresh. */
    Threshold,
}

interface Haptics {
    fun play(signal: HapticSignal)
}

/**
 * Silent by default so a composable used outside [YfuseTheme] — previews, tests — does
 * not have to care. The theme provides the real one.
 */
internal object NoHaptics : Haptics {
    override fun play(signal: HapticSignal) = Unit
}

val LocalHaptics = staticCompositionLocalOf<Haptics> { NoHaptics }

@Composable
expect fun rememberHaptics(): Haptics
