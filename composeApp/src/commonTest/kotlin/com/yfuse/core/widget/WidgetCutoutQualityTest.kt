package com.yfuse.core.widget

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WidgetCutoutQualityTest {
    @Test
    fun clean_connected_subject_has_source_relative_bounds() {
        val mask = subject()
        val quality = assertNotNull(assessWidgetCutoutMask(mask, 100, 100))
        assertEquals(0.14f, quality.top)
        assertEquals(0.84f, quality.bottom)
        assertEquals(0.30f, quality.left)
        assertEquals(0.70f, quality.right)
        assertEquals(mask.count { it >= 0.5f } / 10_000f, quality.foregroundFraction)
    }

    @Test
    fun normal_soft_edges_remain_usable_at_multiple_resolutions() {
        for (size in listOf(32, 80, 320)) {
            val mask =
                FloatArray(size * size) { index ->
                    val x = ((index % size + 0.5f) / size - 0.5f) / 0.22f
                    val y = ((index / size + 0.5f) / size - 0.48f) / 0.34f
                    ((1.03f - sqrt(x * x + y * y)) / 0.10f).coerceIn(0f, 1f)
                }
            assertNotNull(assessWidgetCutoutMask(mask, size, size), "size=$size")
        }
    }

    @Test
    fun a_few_isolated_pixels_do_not_change_subject_bounds() {
        val mask = subject()
        mask[2 * 100 + 2] = 1f
        mask[95 * 100 + 95] = 1f
        val quality = assertNotNull(assessWidgetCutoutMask(mask, 100, 100))
        assertEquals(0.14f, quality.top)
        assertEquals(0.84f, quality.bottom)
    }

    @Test
    fun separate_title_and_multiple_letter_fragments_are_rejected() {
        val title = subject()
        title.fillRect(100, 28, 89, 72, 94)
        assertNull(assessWidgetCutoutMask(title, 100, 100))

        val letters = subject()
        for (left in listOf(30, 42, 54, 66)) {
            letters.fillRect(100, left, 88, left + 4, 94)
        }
        assertNull(assessWidgetCutoutMask(letters, 100, 100))
    }

    @Test
    fun a_second_detached_subject_is_rejected() {
        val mask = subject()
        mask.fillRect(100, 78, 30, 92, 70)
        assertNull(assessWidgetCutoutMask(mask, 100, 100))
    }

    @Test
    fun clipping_on_each_edge_is_rejected_even_for_a_thin_connected_appendage() {
        for (edge in 0..3) {
            val mask = subject()
            when (edge) {
                0 -> mask.fillRect(100, 49, 0, 51, 20)
                1 -> mask.fillRect(100, 43, 80, 45, 100)
                2 -> mask.fillRect(100, 0, 39, 40, 41)
                3 -> mask.fillRect(100, 60, 39, 100, 41)
            }
            assertNull(assessWidgetCutoutMask(mask, 100, 100), "edge=$edge")
        }
    }

    @Test
    fun diffuse_subject_and_background_haze_are_rejected() {
        val diffuse = subject().map { if (it > 0f) 0.65f else 0f }.toFloatArray()
        assertNull(assessWidgetCutoutMask(diffuse, 100, 100))
        val haze = subject().map { if (it > 0f) it else 0.2f }.toFloatArray()
        assertNull(assessWidgetCutoutMask(haze, 100, 100))
    }

    @Test
    fun uniform_small_and_excessively_large_masks_are_rejected() {
        for (value in listOf(0f, 0.2f, 0.5f, 1f)) {
            assertNull(assessWidgetCutoutMask(FloatArray(10_000) { value }, 100, 100))
        }
        val tiny = FloatArray(10_000).apply { fillRect(100, 45, 35, 55, 60) }
        assertNull(assessWidgetCutoutMask(tiny, 100, 100))
        val large = FloatArray(10_000).apply { fillRect(100, 8, 8, 92, 92) }
        assertNull(assessWidgetCutoutMask(large, 100, 100))
    }

    @Test
    fun a_wide_low_subject_has_no_usable_breakout_space() {
        val wide = FloatArray(10_000).apply { fillRect(100, 4, 45, 96, 70) }
        assertNull(assessWidgetCutoutMask(wide, 100, 100))
        val low = FloatArray(10_000).apply { fillRect(100, 30, 65, 70, 95) }
        assertNull(assessWidgetCutoutMask(low, 100, 100))
    }

    @Test
    fun invalid_alpha_and_dimensions_fail_before_allocating_work_buffers() {
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.1f, 1.1f)) {
            val mask = subject().apply { this[0] = value }
            assertNull(assessWidgetCutoutMask(mask, 100, 100))
        }
        assertNull(assessWidgetCutoutMask(floatArrayOf(), 0, 100))
        assertNull(assessWidgetCutoutMask(floatArrayOf(), -100, -100))
        assertNull(assessWidgetCutoutMask(FloatArray(256), 16, 17))
        assertNull(assessWidgetCutoutMask(FloatArray(225), 15, 15))
        assertNull(assessWidgetCutoutMask(floatArrayOf(), 65_536, 65_536))
        assertNull(assessWidgetCutoutMask(floatArrayOf(), Int.MAX_VALUE, Int.MAX_VALUE))
    }

    private fun subject(): FloatArray =
        FloatArray(10_000).apply {
            fillRect(100, 42, 14, 58, 32) // Head.
            fillRect(100, 35, 30, 65, 60) // Torso.
            fillRect(100, 30, 34, 70, 52) // Connected arms.
            fillRect(100, 37, 58, 48, 84) // Legs.
            fillRect(100, 52, 58, 63, 84)
        }

    private fun FloatArray.fillRect(
        width: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        for (y in top until bottom) {
            for (x in left until right) this[y * width + x] = 1f
        }
    }
}
