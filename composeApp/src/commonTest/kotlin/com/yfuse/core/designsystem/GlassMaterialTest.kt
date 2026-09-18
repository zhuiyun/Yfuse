package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassMaterialTest {
    @Test
    fun defaults_preserve_shipped_material_and_previous_preset_restores_original_light_glass() {
        assertEquals(LightPalette.dialogTint, GlassMaterial.defaults(false).tint(false))
        assertEquals(DarkPalette.dialogTint, GlassMaterial.defaults(true).tint(true))
        assertEquals(LightPalette.scrim.alpha, GlassMaterial.defaults(false).scrim, 0.003f)
        assertEquals(DarkPalette.scrim.alpha, GlassMaterial.defaults(true).scrim, 0.003f)
        assertEquals(Color(0xFF878F9B).copy(alpha = 0.52f), GlassMaterial.PreviousLight.tint(false))
        assertEquals(0.16f, GlassMaterial.PreviousLight.scrim)
    }
}
