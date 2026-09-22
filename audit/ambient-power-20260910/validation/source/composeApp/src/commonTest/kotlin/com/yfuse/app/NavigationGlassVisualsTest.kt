package com.yfuse.app

import com.yfuse.core.designsystem.Brand
import com.yfuse.core.designsystem.DarkPalette
import com.yfuse.core.designsystem.LightPalette
import com.yfuse.core.designsystem.resolveAccentColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationGlassVisualsTest {
    @Test
    fun every_navigation_shape_uses_the_shared_glass_shell() {
        listOf(LightPalette, DarkPalette).forEach { palette ->
            val accent = resolveAccentColors(Brand.Primary, palette.isDark)
            val visuals = navigationGlassVisuals(palette, accent)

            assertEquals(palette.glassStrong, visuals.shell)
            assertTrue(visuals.selection.alpha < 1f)
            assertEquals(accent.container.copy(alpha = visuals.selection.alpha), visuals.selection)
        }
    }
}
