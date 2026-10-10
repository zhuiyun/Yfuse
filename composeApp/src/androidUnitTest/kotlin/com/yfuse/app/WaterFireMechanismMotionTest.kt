package com.yfuse.app

import com.yfuse.core.designsystem.SplashAnimation
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WaterFireMechanismMotionTest {
    @Test
    fun scan_lights_from_bottom_to_top_and_closes_every_band_before_launch_fades() {
        val motion = WaterFireMechanismMotion
        assertTrue(motion.bandProgress(400f, 29) > 0f)
        assertEquals(0f, motion.bandProgress(400f, 0))
        repeat(motion.HOLO_BANDS) { band ->
            assertEquals(0f, motion.bandProgress(0f, band))
            assertEquals(1f, motion.bandProgress(motion.HOLO_SETTLED_MS, band))
        }
    }

    @Test
    fun folded_paper_starts_on_one_pivot_ray_and_finishes_without_gaps_or_rotation() {
        val motion = WaterFireMechanismMotion
        repeat(motion.FAN_PLEATS) { pleat ->
            val x = 40f + (pleat + 0.5f) * 24f - 256f
            val y = 256f - 592.96f
            val angle = motion.pleatRotation(0f, pleat) * PiF / 180f
            assertTrue(abs(x * cos(angle) - y * sin(angle)) < 0.001f)
            assertEquals(0f, motion.pleatRotation(motion.FAN_SETTLED_MS, pleat), 0.001f)
        }
    }

    @Test
    fun each_design_finishes_before_the_hand_off_and_reduced_motion_does_not_play_it() {
        val settled =
            mapOf(
                SplashAnimation.Hologram to WaterFireMechanismMotion.HOLO_SETTLED_MS,
                SplashAnimation.Marble to WaterFireMechanismMotion.MARBLE_SETTLED_MS,
                SplashAnimation.Fan to WaterFireMechanismMotion.FAN_SETTLED_MS,
                SplashAnimation.Domino to WaterFireMechanismMotion.DOMINO_SETTLED_MS,
            )
        for ((variant, end) in settled) {
            val duration = variant.motionDurationMs()
            assertTrue(duration - end >= 200f, "${variant.name} needs a resolved hold before the fade")
            val timing = splashTiming(false, false, false, selectedMotionDurationMs = duration)
            assertEquals(0f, splashClockStart(duration.toFloat(), timing.motionDurationMs))
            assertEquals(0, splashTiming(false, true, false, selectedMotionDurationMs = duration).motionDurationMs)
            assertEquals(0, splashTiming(false, false, true, selectedMotionDurationMs = duration).motionDurationMs)
        }
        repeat(3) { layer ->
            assertEquals(1f, WaterFireMechanismMotion.gatherProgress(WaterFireMechanismMotion.MARBLE_SETTLED_MS, layer))
        }
        val lastDomino = WaterFireMechanismMotion.dominoProgress(WaterFireMechanismMotion.DOMINO_SETTLED_MS, 1020f)
        assertEquals(0f, WaterFireMechanismMotion.dominoTilt(lastDomino), 0.001f)
        assertTrue(WaterFireMechanismMotion.dominoTilt(0.64f) < 0f)
        assertTrue(WaterFireMechanismMotion.dominoTilt(0.82f) > 0f)
    }
}
