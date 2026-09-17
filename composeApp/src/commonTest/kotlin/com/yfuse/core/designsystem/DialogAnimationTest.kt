package com.yfuse.core.designsystem

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
        val cache = DialogMotionFrameCache(DialogAnimation.Lift)
        val midway = cache.frame(0.4f)
        assertSame(midway, cache.frame(0.4f))
        val later = cache.frame(0.8f)
        assertNotSame(midway, later)
        assertEquals(dialogMotionFrame(DialogAnimation.Lift, 0.8f), later)
        assertEquals(midway, cache.frame(0.4f))
        assertEquals(DialogMotionFrame(), cache.frame(1f))
        assertSame(cache.frame(1f), cache.frame(2f))
    }

    @Test
    fun only_the_five_shipped_styles_remain() {
        assertEquals(
            listOf(
                DialogAnimation.Lift,
                DialogAnimation.Slide,
                DialogAnimation.Touch,
                DialogAnimation.MagneticDrag,
                DialogAnimation.Cascade,
            ),
            DialogAnimation.entries.toList(),
        )
    }

    @Test
    fun each_choice_survives_preferences_recreation_and_retired_names_fall_back_to_lift() {
        val settings = MapSettings()
        for (animation in DialogAnimation.entries) {
            ThemePreferences(settings).setDialogAnimation(animation)
            assertEquals(animation, ThemePreferences(settings).dialogAnimation.value)
        }
        // Names persisted by builds that offered the retired styles must not crash a launch.
        for (retired in listOf("Hologram", "PosterMorph", "Pinwheel", "Sheen", "future-unknown-style")) {
            settings.putString("appearance.dialogAnimation", retired)
            assertEquals(DialogAnimation.Lift, ThemePreferences(settings).dialogAnimation.value)
        }
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
    fun staged_progress_is_clamped_and_reaches_one() {
        assertEquals(0f, dialogStage(0.1f, 0.24f))
        assertEquals(1f, dialogStage(1f, 0.24f))
        assertEquals(0.5f, dialogStage(0.5f, 0f, 1f))
        assertEquals(1f, dialogStage(2f, 0f, 1f))
    }
}
