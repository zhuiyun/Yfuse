package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 动效预算 (MO3): whether this device, right now, can afford the full motion language.
 *
 * Power saving and low RAM draw as 静息 ([MotionTheme.Calm]), including reduced background blur
 * and ambient-light sampling. The chosen theme is kept and returns when that budget recovers.
 * Temperature is diagnostic only and does not reduce motion.
 */
@Immutable
data class MotionBudget(
    val powerSave: Boolean = false,
    /** Diagnostic observation at or past [THERMAL_STATUS_MODERATE]; does not limit motion. */
    val thermal: Boolean = false,
    val lowRam: Boolean = false,
) {
    val reduced: Boolean get() = powerSave || lowRam
}

/** Android's MODERATE thermal status, used only for diagnostics. */
internal const val THERMAL_STATUS_MODERATE = 2

internal fun isDeviceWarm(status: Int): Boolean = status >= THERMAL_STATUS_MODERATE

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
