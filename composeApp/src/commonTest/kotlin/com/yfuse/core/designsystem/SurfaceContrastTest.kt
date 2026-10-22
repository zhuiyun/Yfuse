package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SurfaceContrastTest {
    @Test
    fun emphasis_is_readable_on_actual_artwork_surfaces_and_its_own_fill() {
        val surfaces =
            listOf(
                Color(0xFF999999),
                Color(0xFF777777),
                Color(0xFFB8D7A8),
                LightPalette.background,
                DarkPalette.background,
            )
        for (surface in surfaces) {
            for (base in listOf(Brand.Primary, Color.Yellow, Color.Red, Color.White, Color.Black)) {
                val colors = resolveAccentColors(base, dark = false, surface = surface)
                assertTrue(artworkPageContrastRatio(colors.accent, surface) >= 4.5f)
                assertTrue(artworkPageContrastRatio(colors.accent, colors.container) >= 4.5f)
                assertTrue(artworkPageContrastRatio(colors.onAccent, colors.accent) >= 4.5f)
            }
        }
    }

    @Test
    fun custom_modal_recipes_keep_content_and_actions_readable_in_both_modes() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            for (opaque in listOf(false, true)) {
                for (preset in GlassMaterialPreset.selectable) {
                    val material = preset.material(palette.isDark)
                    val content = material.contentPalette(palette, opaque)
                    for (ink in listOf(
                        content.text,
                        content.body,
                        content.sub,
                        content.sub2,
                        content.hint,
                        content.error,
                    )) {
                        assertTrue(artworkPageContrastRatio(ink, content.background) >= 4.5f, "${preset.id}: $ink")
                    }
                    val accent = resolveAccentColors(Brand.Primary, content.isDark, content.background)
                    val primary = resolveGlassButtonVisuals(GlassButtonEmphasis.Primary, content, accent)
                    assertTrue(
                        artworkPageContrastRatio(
                            primary.content,
                            modalControlFill(primary.fill, content, opaque).compositeOver(content.background),
                        ) >=
                            4.5f,
                    )
                    assertTrue(content.controlSurface.alpha < 1f)
                }
            }
        }
    }

    @Test
    fun modal_emphasis_and_selection_survive_material_and_opacity_changes() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            val accent = resolveAccentColors(Brand.Primary, palette.isDark)
            assertEquals(1f, modalControlFill(accent.accent, palette, true).alpha)
            assertTrue(modalControlFill(accent.accent, palette).alpha < 1f)
            assertNotEquals(modalControlFill(accent.container, palette), modalControlFill(palette.card2, palette))
            assertEquals(accent.border, modalControlBorder(accent.border, palette))
            assertEquals(accent.border, glassPlateBorder(accent.border, palette, true, false))
            assertEquals(palette.border, palette.controlBorder)
        }
    }

    @Test
    fun mid_tone_custom_panels_keep_neutral_controls_readable() {
        for (palette in listOf(LightPalette, DarkPalette)) {
            for (tint in listOf(0x777777, 0x999999, 0xB8D7A8)) {
                val content =
                    GlassMaterial
                        .defaults(
                            palette.isDark,
                        ).copy(tintRgb = tint, opacity = 1f)
                        .contentPalette(palette, true)
                for (ink in listOf(content.text, content.body, content.sub2)) {
                    assertTrue(
                        artworkPageContrastRatio(ink, content.controlSurface.compositeOver(content.background)) >= 4.5f,
                    )
                }
                assertTrue(content.controlSurface.alpha < 1f)
            }
        }
    }
}
