package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CuriousDialogMotionTest {
    @Test
    fun pinwheel_blades_start_at_zero_area_and_their_whole_bounds_grow_from_the_center() {
        for ((width, height) in sizes) {
            val start = curiousDialogGeometry(DialogAnimation.Pinwheel, width, height, 0f)
            assertEquals(0f, start.bounds.width)
            assertEquals(0f, start.bounds.height)
            for (progress in listOf(0.0001f, 0.001f, 0.01f, 0.25f, 0.55f)) {
                val geometry = curiousDialogGeometry(DialogAnimation.Pinwheel, width, height, progress)
                val bounds = geometry.bounds
                val radiusFraction = progress / 0.88f
                assertTrue(abs(bounds.width - width * radiusFraction) <= 0.0002f)
                assertTrue(abs(bounds.height - height * radiusFraction) <= 0.0002f)
                assertTrue(abs(bounds.center.x - width / 2f) <= 0.0001f)
                assertTrue(abs(bounds.center.y - height / 2f) <= 0.0001f)
            }
        }
    }

    @Test
    fun all_masks_expand_inside_portrait_landscape_and_tiny_panels() {
        for ((width, height) in sizes) {
            for (animation in curiousDialogAnimations) {
                var previous = curiousDialogGeometry(animation, width, height, 0f)
                for (step in 1..100) {
                    val geometry = curiousDialogGeometry(animation, width, height, step / 100f)
                    val b = geometry.bounds
                    assertTrue(b.left >= 0f && b.top >= 0f && b.right <= width && b.bottom <= height)
                    assertTrue(b.width >= 0f && b.height >= 0f)
                    assertTrue(b.left <= previous.bounds.left && b.top <= previous.bounds.top)
                    assertTrue(b.right >= previous.bounds.right && b.bottom >= previous.bounds.bottom)
                    assertTrue(geometry.reveal in previous.reveal..1f)
                    assertTrue(geometry.detail in 0f..previous.detail)
                    previous = geometry
                }
                assertEquals(CuriousDialogGeometry(Rect(0f, 0f, width, height), 1f, 0f), previous)
            }
        }
    }

    @Test
    fun stage_boundaries_are_continuous_in_both_playback_directions() {
        for (animation in curiousDialogAnimations) {
            for (boundary in listOf(0.08f, 0.24f, 0.30f, 0.36f, 0.84f, 0.86f, 0.88f, 0.92f)) {
                val before = curiousDialogGeometry(animation, 320f, 480f, boundary - 0.00001f)
                val after = curiousDialogGeometry(animation, 320f, 480f, boundary + 0.00001f)
                assertTrue(abs(before.bounds.left - after.bounds.left) < 0.05f, "$animation at $boundary")
                assertTrue(abs(before.bounds.top - after.bounds.top) < 0.05f, "$animation at $boundary")
                assertTrue(abs(before.bounds.right - after.bounds.right) < 0.05f, "$animation at $boundary")
                assertTrue(abs(before.bounds.bottom - after.bounds.bottom) < 0.05f, "$animation at $boundary")
                assertTrue(abs(before.reveal - after.reveal) < 0.001f)
                assertTrue(abs(before.detail - after.detail) < 0.001f)
            }
        }
        // Full bounds do not yet mean a full silhouette: these corner cuts must still be drawn.
        val constellation = curiousDialogGeometry(DialogAnimation.Constellation, 320f, 480f, 0.845f)
        assertEquals(1f, constellation.reveal)
        assertTrue(constellation.detail > 0f)
    }

    @Test
    fun text_keeps_its_proportions_and_transforms_settle_before_the_masks() {
        for (animation in curiousDialogAnimations) {
            for (step in 0..100) {
                val frame = curiousDialogMotionFrame(animation, step / 100f)
                assertEquals(frame.scaleX, frame.scaleY)
                assertTrue(frame.scaleX in 0.97f..1f)
                assertTrue(abs(frame.rotationZ) <= 4f && frame.rotationX == 0f)
                assertTrue(abs(frame.offsetY) <= 12f && frame.offsetX == 0f)
            }
            for (progress in listOf(0.86f, 0.88f, 0.92f, 1f, 2f)) {
                assertEquals(DialogMotionFrame(), curiousDialogMotionFrame(animation, progress))
            }
        }
    }

    @Test
    fun every_style_has_a_full_undecorated_endpoint_before_final_bypass() {
        for (animation in curiousDialogAnimations) {
            for ((width, height) in sizes) {
                for (progress in listOf(0.88f, 0.9f, 0.92f, 1f, 2f)) {
                    assertEquals(
                        CuriousDialogGeometry(Rect(0f, 0f, width, height), 1f, 0f),
                        curiousDialogGeometry(animation, width, height, progress),
                    )
                    assertEquals(0f, curiousDialogDecoration(progress))
                }
            }
        }
    }

    @Test
    fun decorations_are_one_finite_pulse_and_styles_have_distinct_reveals() {
        var previous = 0f
        for (step in 0..44) {
            val pulse = curiousDialogDecoration(step / 100f)
            assertTrue(pulse in previous..1f)
            previous = pulse
        }
        for (step in 45..100) {
            val pulse = curiousDialogDecoration(step / 100f)
            assertTrue(pulse in 0f..previous)
            previous = pulse
        }
        assertEquals(0f, curiousDialogDecoration(-1f))
        assertEquals(0f, curiousDialogDecoration(2f))
        val signatures =
            curiousDialogAnimations.map {
                curiousDialogMotionFrame(it, 0.4f) to curiousDialogGeometry(it, 320f, 480f, 0.4f)
            }
        assertEquals(5, signatures.distinct().size)
    }

    private val sizes = listOf(320f to 480f, 560f to 200f, 1f to 1f, 0.01f to 0.1f, 0f to 0f)
}
