package com.yfuse.core.designsystem

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoadingAnimationTest {
    private val dots =
        listOf(
            LoadingAnimation.Beads,
            LoadingAnimation.BeadWave,
            LoadingAnimation.BeadBreath,
            LoadingAnimation.BeadRelay,
        )

    @Test
    fun dot_cycles_join_without_jumps_and_stay_inside_small_icon_slots() {
        dots.forEach { animation ->
            repeat(3) { index ->
                val start = loadingDotFrame(animation, 0f, index)
                val end = loadingDotFrame(animation, 1f - 0.00001f, index)
                assertTrue(abs(start.x - end.x) < 0.01f, "$animation x discontinuity")
                assertTrue(abs(start.y - end.y) < 0.01f, "$animation y discontinuity")
                assertTrue(abs(start.scaleX - end.scaleX) < 0.01f)
                assertTrue(abs(start.alpha - end.alpha) < 0.01f)
                repeat(301) { frame ->
                    val dot = loadingDotFrame(animation, frame / 300f, index)
                    val radius = 4.5f * maxOf(dot.scaleX, dot.scaleY)
                    assertTrue(dot.x - radius >= 0f && dot.x + radius <= 56f)
                    assertTrue(dot.y - radius >= 0f && dot.y + radius <= 56f)
                    assertTrue(dot.alpha in 0f..1f)
                }
            }
        }
    }

    @Test
    fun reduced_motion_keeps_three_separate_visible_dots_at_every_phase() {
        dots.forEach { animation ->
            repeat(3) { index ->
                val resting = loadingDotFrame(animation, 0f, index, moving = false)
                assertEquals(1f, resting.alpha)
                assertEquals(28f, resting.y)
                assertEquals(12f + index * 16f, resting.x)
                assertEquals(resting, loadingDotFrame(animation, 0.7f, index, moving = false))
            }
        }
    }

    @Test
    fun relay_beads_remain_distinct_while_passing_each_other() {
        repeat(301) { frame ->
            val positions = (0..2).map { loadingDotFrame(LoadingAnimation.BeadRelay, frame / 300f, it) }
            repeat(3) { index ->
                val a = positions[index]
                val b = positions[(index + 1) % 3]
                assertTrue(hypot(a.x - b.x, a.y - b.y) >= 9f, "Relay beads overlap at frame $frame")
            }
        }
    }
}
