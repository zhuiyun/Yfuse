package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThemeCrossfadeTest {
    private val light = ThemeColors(LightPalette, resolveAccentColors(Brand.Primary, dark = false))
    private val dark = ThemeColors(DarkPalette, resolveAccentColors(Color(0xFFC94FA0), dark = true))

    @Test
    fun ends_of_the_crossfade_are_the_source_and_destination_sets() {
        assertEquals(light, lerpThemeColors(light, dark, 0f))
        assertEquals(dark, lerpThemeColors(light, dark, 1f))
    }

    @Test
    fun midway_the_paint_is_between_but_the_policy_is_already_the_destination() {
        val mid = lerpThemeColors(light, dark, 0.5f)
        assertTrue(mid.palette.isDark)
        val lightLum = light.palette.background.red
        val darkLum = dark.palette.background.red
        val midLum = mid.palette.background.red
        assertTrue(midLum < lightLum && midLum > darkLum)
        assertTrue(mid.accent.accent != light.accent.accent && mid.accent.accent != dark.accent.accent)
    }
}
