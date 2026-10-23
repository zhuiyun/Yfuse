package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlassButtonStyleTest {
    @Test
    fun primary_and_destructive_actions_preserve_readable_semantic_fills() {
        val accent = resolveAccentColors(Brand.Primary, dark = false)

        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, LightPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, LightPalette, accent)

        assertTrue(primary.fill.alpha < 1f)
        assertTrue(primary.sheen > 0f)
        assertTrue(destructive.fill.alpha < 1f)
        assertTrue(
            artworkPageContrastRatio(primary.content, primary.fill.compositeOver(LightPalette.background)) >= 4.5f,
        )
        assertTrue(
            artworkPageContrastRatio(destructive.content, destructive.fill.compositeOver(LightPalette.background)) >=
                4.5f,
        )
        assertNotNull(primary.border)
        assertNotNull(destructive.border)
    }

    @Test
    fun neutral_action_keeps_a_luminous_edge_and_translucent_surface() {
        val accent = resolveAccentColors(Brand.Primary, dark = false)

        val neutral = resolveGlassButtonVisuals(GlassButtonEmphasis.Neutral, LightPalette, accent)

        assertNotNull(neutral.border)
        assertTrue(neutral.fill.alpha < 1f)
    }

    @Test
    fun dark_actions_choose_ink_for_the_final_fill() {
        val accent = resolveAccentColors(Brand.Primary, dark = true)
        val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, DarkPalette, accent)
        val destructive =
            resolveGlassButtonVisuals(GlassButtonEmphasis.Destructive, DarkPalette, accent)

        assertTrue(
            artworkPageContrastRatio(primary.content, primary.fill.compositeOver(DarkPalette.background)) >= 4.5f,
        )
        assertTrue(
            artworkPageContrastRatio(destructive.content, destructive.fill.compositeOver(DarkPalette.background)) >=
                4.5f,
        )
        assertTrue(primary.sheen <= 0.5f)
        assertTrue(destructive.sheen <= 0.5f)
    }

    @Test
    fun light_action_edges_remain_highlights_for_every_emphasis_and_accent() {
        for (color in listOf(Brand.Primary, Color.Black, Color.White, Color(0xFF351940), Color(0xFFFFD044))) {
            val accent = resolveAccentColors(color, dark = false)
            for (emphasis in GlassButtonEmphasis.entries) {
                val visual = resolveGlassButtonVisuals(emphasis, LightPalette, accent)
                val border = assertNotNull(visual.border)
                val body = visual.fill.compositeOver(LightPalette.background)
                assertTrue(border.compositeOver(body).luminance() >= 0.85f, "Light actions need luminous edges")
            }
        }
    }

    @Test
    fun form_and_overlay_buttons_share_one_disabled_alpha() {
        assertEquals(1f, glassButtonAlpha(enabled = true))
        assertEquals(0.44f, glassButtonAlpha(enabled = false))
    }

    @Test
    fun action_text_stays_readable_after_modal_and_frosted_fill_remapping() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            val accent = resolveAccentColors(Brand.Primary, palette.isDark, palette.background)
            for (emphasis in GlassButtonEmphasis.entries) {
                val visual = resolveGlassButtonVisuals(emphasis, palette, accent)
                val frosted = resolveFrostedMaterialTones(visual.fill, palette, GlassWeight.Strong.frostDensity)
                val surfaces =
                    mapOf(
                        "modal" to modalControlFill(visual.fill, palette).compositeOver(palette.background),
                        "opaque" to modalControlFill(visual.fill, palette, reduceTransparency = true),
                        "frosted-top" to frosted.top.compositeOver(palette.background),
                        "frosted-body" to frosted.body.compositeOver(palette.background),
                        "frosted-bottom" to frosted.bottom.compositeOver(palette.background),
                    )
                for ((material, surface) in surfaces) {
                    val contrast = artworkPageContrastRatio(visual.content, surface)
                    assertTrue(
                        contrast >= 4.5f,
                        "${palette.isDark}/$emphasis/$material contrast=$contrast must remain readable after compositing",
                    )
                }
            }
        }
    }
}
