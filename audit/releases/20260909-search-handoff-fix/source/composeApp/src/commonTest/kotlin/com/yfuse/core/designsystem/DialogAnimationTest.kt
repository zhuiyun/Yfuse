package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Offset
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ThemePreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DialogAnimationTest {
    @Test
    fun layer_and_mask_share_geometry_without_stale_frames_when_exit_reverses() {
        val cache = DialogMotionFrameCache(DialogAnimation.Spring)
        val midway = cache.frame(0.4f)
        assertSame(midway, cache.frame(0.4f))
        val later = cache.frame(0.8f)
        assertNotSame(midway, later)
        assertEquals(dialogMotionFrame(DialogAnimation.Spring, 0.8f), later)
        assertEquals(midway, cache.frame(0.4f))
        assertEquals(DialogMotionFrame(), cache.frame(1f))
        assertSame(cache.frame(1f), cache.frame(2f))
    }

    @Test
    fun mask_only_styles_reuse_identity_geometry_across_animation_frames() {
        val cache = DialogMotionFrameCache(DialogAnimation.Mosaic)
        assertSame(cache.frame(0.1f), cache.frame(0.9f))
        assertSame(cache.frame(0.9f), cache.frame(1f))
    }

    @Test
    fun each_choice_survives_preferences_recreation_and_unknown_values_fall_back() {
        val settings = MapSettings()
        for (animation in DialogAnimation.entries) {
            ThemePreferences(settings).setDialogAnimation(animation)
            assertEquals(animation, ThemePreferences(settings).dialogAnimation.value)
        }
        settings.putString("appearance.dialogAnimation", "future-unknown-style")
        assertEquals(DialogAnimation.Lift, ThemePreferences(settings).dialogAnimation.value)
    }

    @Test
    fun every_style_finishes_at_identity_and_keeps_geometry_in_bounds() {
        for (animation in DialogAnimation.entries) {
            assertEquals(DialogMotionFrame(), dialogMotionFrame(animation, 1f))
            for (step in 0..100) {
                val frame = dialogMotionFrame(animation, step / 100f)
                assertTrue(frame.scaleX >= 0f && frame.scaleY >= 0f)
                assertTrue(frame.insetX in 0f..0.5f && frame.insetY in 0f..0.5f)
            }
            assertEquals(0, overlayDurationMillis(false, true, animation))
            assertEquals(0, overlayDurationMillis(true, true, animation))
            assertTrue(animation.exitMillis < animation.enterMillis)
        }
    }

    @Test
    fun portal_reaches_every_corner_even_when_trigger_is_outside_the_panel() {
        for (origin in listOf(Offset(0f, 0f), Offset(160f, 100f), Offset(400f, -80f))) {
            val radius = dialogPortalRadius(320f, 200f, origin)
            for (corner in listOf(Offset.Zero, Offset(320f, 0f), Offset(0f, 200f), Offset(320f, 200f))) {
                assertTrue((corner - origin).getDistance() <= radius + 0.001f)
            }
        }
    }

    @Test
    fun reconstruction_slices_reveal_monotonically_and_reassemble_without_offsets() {
        for (index in 0 until 6) {
            assertEquals(0f, dialogSlice(0f, index).reveal)
            val finished = dialogSlice(1f, index)
            assertEquals(1f, finished.reveal)
            assertTrue(kotlin.math.abs(finished.offset) < 0.001f)
            var previous = 0f
            for (step in 0..100) {
                val slice = dialogSlice(step / 100f, index)
                assertTrue(slice.reveal in previous..1f)
                previous = slice.reveal
            }
        }
        assertEquals(0f, dialogStage(0.1f, 0.24f))
        assertEquals(1f, dialogStage(1f, 0.24f))
    }
}
