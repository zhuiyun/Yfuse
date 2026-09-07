package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MaterialDialogMotionTest {
    @Test
    fun liquid_reveal_grows_from_center_without_exceeding_panel_bounds() {
        for ((width, height) in listOf(320f to 540f, 561f to 201f)) {
            val center = Offset(width / 2f, height / 2f)
            assertEquals(Rect(center.x, center.y, center.x, center.y), liquidDialogBounds(width, height, 0f))
            assertEquals(Rect(0f, 0f, width, height), liquidDialogBounds(width, height, 1f))
            var previousWidth = 0f
            var previousHeight = 0f
            for (step in 0..100) {
                val bounds = liquidDialogBounds(width, height, step / 100f)
                assertEquals(center.x, bounds.center.x, 0.001f)
                assertEquals(center.y, bounds.center.y, 0.001f)
                assertTrue(bounds.width in previousWidth..width)
                assertTrue(bounds.height in previousHeight..height)
                previousWidth = bounds.width
                previousHeight = bounds.height
            }
        }
    }

    @Test
    fun four_pieces_share_exact_edges_and_cover_odd_sized_panels() {
        val tiles = (0 until 4).map { dialogQuadrant(561f, 201f, it) }
        assertEquals(tiles[0].right, tiles[1].left)
        assertEquals(tiles[0].bottom, tiles[2].top)
        assertEquals(tiles[1].bottom, tiles[3].top)
        assertEquals(tiles[2].right, tiles[3].left)
        assertEquals(0f, tiles[0].left)
        assertEquals(0f, tiles[0].top)
        assertEquals(561f, tiles[3].right)
        assertEquals(201f, tiles[3].bottom)
        assertEquals(561f * 201f, tiles.sumOf { (it.width * it.height).toDouble() }.toFloat())
    }
}
