package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 动效预算 (MO3): whether this device, right now, can afford the full motion language.
 *
 * The way Telegram does it rather than playing everything and dropping frames: a phone in 省电模式,
 * one warming up — a thermal status of MODERATE or worse — or a low-RAM device is drawn as 静息
 * ([MotionTheme.Calm]: fades and short moves, no particles, tides or bounces), and the costliest
 * decoration goes with it: the live background blur and 氛围光's frame sampling. The person's own
 * choice of theme is kept and comes back as the device recovers.
 *
 * It changes when the device does — a switch in the quick settings, a phone heating up in the sun —
 * and is read at composition, never per frame.
 */
@Immutable
data class MotionBudget(
    val powerSave: Boolean = false,
    /** Thermal status at or past [THERMAL_STATUS_MODERATE]. */
    val thermal: Boolean = false,
    val lowRam: Boolean = false,
) {
    /** Any of the three: spend less. */
    val reduced: Boolean get() = powerSave || thermal || lowRam
}

/** `PowerManager.THERMAL_STATUS_MODERATE`, the point at which Android asks apps to cut back. */
internal const val THERMAL_STATUS_MODERATE = 2

/** Whether Android's thermal [status] (0 for none) is one to cut back at. */
internal fun thermallyConstrained(status: Int): Boolean = status >= THERMAL_STATUS_MODERATE

/** The theme to draw with: [chosen], or 静息 while [budget] is reduced. */
internal fun effectiveMotionTheme(
    chosen: MotionTheme,
    budget: MotionBudget,
): MotionTheme = if (budget.reduced) MotionTheme.Calm else chosen

/** The budget [YfuseTheme] drew with; the full one outside it (previews, tests). */
val LocalMotionBudget = staticCompositionLocalOf { MotionBudget() }

/** This device's [MotionBudget], kept current as power saving and the thermal status change. */
@Composable
internal expect fun rememberMotionBudget(): MotionBudget
