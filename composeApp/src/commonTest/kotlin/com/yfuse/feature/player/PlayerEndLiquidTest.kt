package com.yfuse.feature.player

import com.yfuse.core.designsystem.LiquidTestSupport
import com.yfuse.core.designsystem.liquidOutline
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlayerEndLiquidTest {
    // 52 dp keys in 66 dp touch boxes, 18 dp apart: centres 84 dp apart.
    private val three = PlayerEndMetrics(hasNext = true, radius = 26f, step = 84f)
    private val two = three.copy(hasNext = false)

    private fun pieces(frame: PlayerEndLiquidFrame) = liquidOutline(frame.bodies(), -200f, 200f, 40f)

    @Test
    fun the_ending_appears_as_one_drop() {
        listOf(three, two).forEach { metrics ->
            val drop = pieces(playerEndFrame(PlayerEndMove.Split, 0f, metrics)).single()
            assertEquals(-26f, drop.start, 0.1f)
            assertEquals(26f, drop.end, 0.1f)
        }
    }

    @Test
    fun the_pinch_times_are_where_the_engine_breaks_the_last_neck() {
        val threeKeys = LiquidTestSupport.firstTimeWith(3) { ms -> pieces(playerEndSplitFrame(ms, three)).size }
        val twoKeys = LiquidTestSupport.firstTimeWith(2) { ms -> pieces(playerEndSplitFrame(ms, two)).size }
        assertNotNull(threeKeys)
        assertNotNull(twoKeys)
        assertTrue(abs(threeKeys - PLAYER_END_PINCH_MS) <= 4f, "three keys break at $threeKeys ms")
        assertTrue(abs(twoKeys - PLAYER_END_PINCH_SINGLE_MS) <= 4f, "two keys break at $twoKeys ms")
    }

    @Test
    fun the_keys_land_where_the_row_lays_them_out() {
        listOf(three, two).forEach { metrics ->
            val last = playerEndFrame(PlayerEndMove.Split, PlayerEndMove.Split.durationMs.toFloat(), metrics)
            val landed = pieces(last)
            assertEquals(metrics.centers.size, landed.size)
            landed.zip(metrics.centers).forEach { (piece, center) ->
                assertEquals(center, piece.center, 0.3f)
                assertEquals(26f, piece.peak, 0.1f)
            }
            last.drops.forEach { assertEquals(1f, it.glyph) }
        }
    }

    @Test
    fun only_the_ring_keys_empty() {
        val last = playerEndFrame(PlayerEndMove.Split, PlayerEndMove.Split.durationMs.toFloat(), three)
        assertEquals(listOf(0f, 1f, 1f), last.drops.map { it.hollow })
        val single = playerEndFrame(PlayerEndMove.Split, PlayerEndMove.Split.durationMs.toFloat(), two)
        // Without 下一集, 重播 stays solid and 返回 empties.
        assertEquals(listOf(0f, 1f), single.drops.map { it.hollow })
    }

    @Test
    fun keys_empty_only_after_they_have_come_apart() {
        (0..PLAYER_END_SPLIT_MS step 4).forEach { ms ->
            val frame = playerEndSplitFrame(ms.toFloat(), three)
            if (frame.drops.any { it.hollow > 0f }) assertEquals(3, pieces(frame).size, "hollowing at $ms ms")
        }
    }

    @Test
    fun replay_draws_everything_back_and_fades_it() {
        listOf(three, two).forEach { metrics ->
            val first = playerEndFrame(PlayerEndMove.Merge, 0f, metrics)
            assertEquals(metrics.centers.size, pieces(first).size)
            val last = playerEndFrame(PlayerEndMove.Merge, PlayerEndMove.Merge.durationMs.toFloat(), metrics)
            assertEquals(1, pieces(last).size)
            assertEquals(0f, last.alpha)
            last.drops.forEach { assertEquals(0f, it.glyph) }
        }
    }

    @Test
    fun splitting_from_the_paused_key_skips_the_arrival() {
        val fromKey = playerEndFrame(PlayerEndMove.SplitFromPausedKey, 200f, three)
        val arrived = playerEndFrame(PlayerEndMove.Split, 200f + PLAYER_END_SPLIT_LEAD_MS, three)
        assertEquals(arrived.drops.map { it.center }, fromKey.drops.map { it.center })
    }
}
