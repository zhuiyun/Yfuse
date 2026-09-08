package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DelightDialogMotionTest {
    @Test
    fun new_styles_finish_before_the_endpoint_without_distorting_text() {
        for (animation in delightDialogAnimations) {
            for (step in 0..100) {
                val frame = delightDialogMotionFrame(animation, step / 100f)
                assertEquals(frame.scaleX, frame.scaleY, "Text proportions changed for $animation")
                assertTrue(frame.scaleX in 0.95f..1f)
                assertTrue(abs(frame.rotationZ) <= 6f)
                assertTrue(abs(frame.offsetX) <= 42f && abs(frame.offsetY) <= 18f)
                assertEquals(0f, frame.rotationX)
            }
            for (progress in listOf(0.88f, 0.92f, 1f, 1.2f)) {
                assertEquals(DialogMotionFrame(), delightDialogMotionFrame(animation, progress))
            }
        }
    }

    @Test
    fun masks_grow_within_portrait_landscape_and_tiny_panels() {
        for ((width, height) in listOf(320f to 540f, 561f to 201f, 1f to 1f, 0f to 0f)) {
            for (animation in delightDialogAnimations) {
                var previous = delightDialogMask(animation, width, height, 0f).bounds
                assertTrue(previous.width == 0f || previous.height == 0f)
                for (step in 1..100) {
                    val mask = delightDialogMask(animation, width, height, step / 100f)
                    val bounds = mask.bounds
                    assertTrue(bounds.left >= 0f && bounds.top >= 0f)
                    assertTrue(bounds.right <= width && bounds.bottom <= height)
                    assertTrue(bounds.left <= previous.left && bounds.top <= previous.top)
                    assertTrue(bounds.right >= previous.right && bounds.bottom >= previous.bottom)
                    assertTrue(mask.edgeInset in 0f..(bounds.width / 2f))
                    assertTrue(mask.toothDepth in 0f..(bounds.height / 4f))
                    previous = bounds
                }
                assertEquals(Rect(0f, 0f, width, height), previous)
            }
        }
    }

    @Test
    fun masks_have_no_remaining_corners_teeth_or_seams_before_final_bypass() {
        for (animation in delightDialogAnimations) {
            for (progress in listOf(0.915f, 0.92f, 0.999f, 1f, 2f)) {
                assertEquals(
                    DelightDialogMask(Rect(0f, 0f, 340f, 520f)),
                    delightDialogMask(animation, 340f, 520f, progress),
                    "$animation retained a mask detail at $progress",
                )
            }
        }
    }

    @Test
    fun decorations_make_one_finite_pulse_and_disappear_before_reading_rest() {
        assertEquals(0f, delightDialogDecoration(-1f))
        assertEquals(0f, delightDialogDecoration(0f))
        var previous = 0f
        for (step in 1..45) {
            val intensity = delightDialogDecoration(step / 100f)
            assertTrue(intensity in previous..1f)
            previous = intensity
        }
        for (step in 46..90) {
            val intensity = delightDialogDecoration(step / 100f)
            assertTrue(intensity in 0f..previous)
            previous = intensity
        }
        for (progress in listOf(0.9f, 0.92f, 1f, 2f)) assertEquals(0f, delightDialogDecoration(progress))
    }

    @Test
    fun each_object_has_a_distinct_motion_and_reveal_instead_of_only_a_new_label() {
        val signatures =
            delightDialogAnimations.map { animation ->
                delightDialogMotionFrame(animation, 0.35f) to delightDialogMask(animation, 320f, 480f, 0.35f)
            }
        assertEquals(delightDialogAnimations.size, signatures.distinct().size)
        assertTrue(delightDialogMotionFrame(DialogAnimation.WindChime, 0.15f).rotationZ > 0f)
        assertTrue(delightDialogMotionFrame(DialogAnimation.WindChime, 0.44f).rotationZ < 0f)
        assertEquals(0f, delightDialogMask(DialogAnimation.InstantPhoto, 320f, 480f, 0.08f).bounds.height)
        assertTrue(delightDialogMask(DialogAnimation.Zipper, 320f, 480f, 0.3f).edgeInset > 0f)
        assertTrue(delightDialogMask(DialogAnimation.Ticket, 320f, 480f, 0.3f).toothDepth > 0f)
    }

    @Test
    fun reduced_motion_endpoint_is_the_full_undecorated_card() {
        for (animation in delightDialogAnimations) {
            assertEquals(DialogMotionFrame(), delightDialogMotionFrame(animation, 1f))
            assertEquals(
                DelightDialogMask(Rect(0f, 0f, 320f, 480f)),
                delightDialogMask(animation, 320f, 480f, 1f),
            )
            assertEquals(0f, delightDialogDecoration(1f))
        }
    }
}
