package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

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
}
