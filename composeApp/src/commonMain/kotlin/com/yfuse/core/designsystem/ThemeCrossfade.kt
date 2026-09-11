package com.yfuse.core.designsystem

import androidx.compose.runtime.Immutable

/** Stable destination colours, published once per theme change. Consumers own their paint clocks. */
@Immutable
data class ThemeColors(
    val palette: Palette,
    val accent: AccentColors,
)

const val THEME_CROSSFADE_MS = Motion.THEME_CROSSFADE
