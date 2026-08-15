package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlassTest {
    @Test
    fun opaque_composite_removes_transparency_without_discarding_semantic_colour() {
        val background = Color(0xFFF3F5F8)
        val accent = Color(0xFF3D64C9).copy(alpha = 0.72f)

        val resolved = opaqueComposite(accent, background)

        assertEquals(1f, resolved.alpha)
        assertNotEquals(background, resolved)
        assertNotEquals(Color.White, resolved)
    }

    @Test
    fun dark_or_semantic_material_borders_are_removed() {
        val darkAccent = Color(0xFF244A9A)
        val error = Color(0xFFB4232E)

        assertNull(resolveGlassMaterialBorder(darkAccent, LightPalette))
        assertNull(resolveGlassMaterialBorder(error, DarkPalette))
    }

    @Test
    fun luminous_material_edge_keeps_its_requested_strength() {
        val subtleHighlight = Color.White.copy(alpha = 0.16f)

        assertEquals(subtleHighlight, resolveGlassMaterialBorder(subtleHighlight, LightPalette))
        assertEquals(DarkPalette.border, resolveGlassMaterialBorder(DarkPalette.border, DarkPalette))
    }

    @Test
    fun absent_material_border_stays_absent() {
        assertNull(resolveGlassMaterialBorder(null, LightPalette))
        assertNull(resolveGlassMaterialBorder(null, DarkPalette))
    }

    @Test
    fun frosted_material_has_diffused_light_and_depth_in_both_themes() {
        listOf(LightPalette.card2 to LightPalette, DarkPalette.card2 to DarkPalette).forEach { (fill, palette) ->
            val tones = resolveFrostedMaterialTones(fill, palette)

            assertTrue(tones.top.luminance() > tones.body.luminance())
            assertTrue(tones.bottom.luminance() < tones.body.luminance())
            assertTrue(tones.body.alpha >= fill.alpha)
        }
    }

    @Test
    fun stronger_frosted_surfaces_have_more_body_than_quiet_ones() {
        val quiet = resolveFrostedMaterialTones(LightPalette.card2, LightPalette, density = 0.88f)
        val strong = resolveFrostedMaterialTones(LightPalette.card2, LightPalette, density = 1.10f)

        assertTrue(strong.body.alpha > quiet.body.alpha)
        assertNotEquals(strong.body, quiet.body)
    }
}
