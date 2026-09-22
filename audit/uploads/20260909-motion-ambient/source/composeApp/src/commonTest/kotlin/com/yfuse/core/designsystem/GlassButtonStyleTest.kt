package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassButtonStyleTest {
    @Test
    fun primary_and_destructive_actions_remain_translucent_glass() {
        val accent = AccentColors(Color.Blue, Color.White, Color.Blue, Color.Blue)

        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, LightPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, LightPalette, accent)

        assertTrue(primary.fill.alpha < 1f)
        assertTrue(destructive.fill.alpha < 1f)
        assertEquals(accent.accent, primary.content)
        assertEquals(LightPalette.error, destructive.content)
        assertNull(primary.border)
        assertNull(destructive.border)
    }

    @Test
    fun neutral_action_keeps_only_the_palette_highlight_edge() {
        val accent = resolveAccentColors(Brand.Primary, dark = false)

        val neutral = resolveGlassButtonVisuals(GlassButtonEmphasis.Neutral, LightPalette, accent)

        assertEquals(LightPalette.border, neutral.border)
    }

    @Test
    fun dark_actions_use_light_ink_and_restrained_sheen() {
        val accent = resolveAccentColors(Brand.Primary, dark = true)
        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, DarkPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, DarkPalette, accent)

        assertEquals(DarkPalette.text, primary.content)
        assertEquals(DarkPalette.onErrorContainer, destructive.content)
        assertTrue(primary.sheen <= 0.5f)
        assertTrue(destructive.sheen <= 0.5f)
    }

    @Test
    fun form_and_overlay_buttons_share_one_disabled_alpha() {
        assertEquals(1f, glassButtonAlpha(enabled = true))
        assertEquals(0.44f, glassButtonAlpha(enabled = false))
    }
}
