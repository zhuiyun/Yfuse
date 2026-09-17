package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeekFeedbackTest {
    @Test
    fun direction_changes_the_tail_without_moving_the_seek_position() {
        val rightward = seekBulge(300f, 0.4f, 24f, 1f, 1f)
        val leftward = seekBulge(300f, 0.4f, 24f, -1f, 1f)
        assertEquals(120f, rightward.center)
        assertEquals(rightward.center, leftward.center)
        assertTrue(rightward.center - rightward.left > rightward.right - rightward.center)
        assertTrue(leftward.center - leftward.left < leftward.right - leftward.center)
    }

    @Test
    fun narrow_rails_and_endpoints_never_draw_outside_the_track() {
        for (width in listOf(1f, 12f, 300f)) {
            for (fraction in listOf(-1f, 0f, 0.01f, 0.5f, 0.99f, 1f, 2f)) {
                for (direction in listOf(-10f, 0f, 10f)) {
                    val shape = seekBulge(width, fraction, 24f, direction, 1.3f)
                    assertTrue(shape.left >= 0f && shape.right <= width)
                    assertTrue(shape.center in shape.left..shape.right)
                    assertTrue(shape.strength in 0f..1f)
                }
            }
        }
        assertEquals(0f, seekBulge(300f, 0f, 24f, 1f, 1f).strength)
        assertEquals(0f, seekBulge(300f, 1f, 24f, -1f, 1f).strength)
    }

    @Test
    fun release_and_invalid_geometry_remove_deformation() {
        assertEquals(0f, seekBulge(300f, 0.5f, 24f, 1f, 0f).strength)
        assertEquals(0f, seekBulge(0f, 0.5f, 24f, 1f, 1f).strength)
        assertEquals(0f, seekBulge(300f, Float.NaN, 24f, 1f, 1f).strength)
    }
}
