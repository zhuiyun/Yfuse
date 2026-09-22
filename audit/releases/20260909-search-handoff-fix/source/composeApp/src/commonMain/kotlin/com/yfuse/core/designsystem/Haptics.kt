package com.yfuse.core.designsystem

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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

/**
 * Ticks once when a pull crosses the point where letting go would refresh.
 *
 * Pull-to-refresh is the one gesture in the app whose commit point is invisible until it
 * has already happened — the indicator only spins after release. The tick is what tells a
 * thumb it can stop pulling. Re-arms on the way back, so a pull that hovers around the
 * threshold does not rattle.
 *
 * [refreshing] is not optional in spirit. A refresh nobody pulled for — a cold start's
 * first load, a retry button, a server switch — drives the very same indicator through the
 * very same threshold, and this fired for it: opening the app buzzed the phone, from a
 * gesture haptic, with no gesture. Pass the flag the `PullToRefreshBox` is given and the
 * tick stays attached to the finger that earned it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshThresholdHaptics(
    state: PullToRefreshState,
    refreshing: Boolean = false,
) {
    val haptics = LocalHaptics.current
    // Read through a holder: the effect outlives any single value of [refreshing], and
    // restarting it on every change would rearm the tick mid-pull.
    val programmatic by rememberUpdatedState(refreshing)
    LaunchedEffect(state, haptics) {
        var armed = false
        snapshotFlow { state.distanceFraction >= 1f }.collect { past ->
            if (past && !armed && !programmatic) haptics.play(HapticSignal.Threshold)
            armed = past
        }
    }
}
