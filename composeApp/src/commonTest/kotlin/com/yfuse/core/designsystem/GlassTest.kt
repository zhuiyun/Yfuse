package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

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
}
