package com.yfuse.feature.detail

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailActionColorTest {
    @Test
    fun primary_play_key_keeps_the_artwork_colour_without_brand_blending() {
        val artwork = Color(0xFFE8B32A)
        assertEquals(artwork, primaryActionColor(artwork))
    }

    @Test
    fun item_fallback_is_not_one_fixed_colour() {
        val first = detailArtworkFallbackColor("server-a" to "item-1")
        val second = detailArtworkFallbackColor("server-a" to "item-2")
        kotlin.test.assertNotEquals(first, second)
    }

    @Test
    fun primary_play_key_selects_readable_ink_for_bright_and_dark_artwork() {
        assertEquals(Color.Black, primaryActionContentColor(Color(0xFFF2D65C)))
        assertEquals(Color.White, primaryActionContentColor(Color(0xFF15213A)))
    }
}
