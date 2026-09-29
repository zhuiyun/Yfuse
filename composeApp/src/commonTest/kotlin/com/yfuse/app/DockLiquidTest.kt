package com.yfuse.app

import com.yfuse.core.designsystem.liquidOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockLiquidTest {
    // A 412 dp phone: the dock row between the two 14 dp margins, 62 dp tall, 14 dp to 搜索.
    private val metrics = DockLiquidMetrics(width = 384f, height = 62f, gap = 14f)

    private fun pieces(frame: DockLiquidFrame) = liquidOutline(frame.bodies(metrics), 0f, metrics.width, 60f)

    @Test
    fun the_dock_rises_as_one_capsule_across_the_whole_row() {
        val first = dockLiquidFrame(DockLiquidMove.Enter, 0f, metrics)
        val capsule = pieces(first).single()
        assertEquals(0f, capsule.start, 0.1f)
        assertEquals(metrics.width, capsule.end, 0.1f)
        assertEquals(0f, first.glyph)
        assertEquals(metrics.width, first.tabSpan, 0.01f)
    }

    @Test
    fun search_lands_where_the_resting_key_is() {
        val last = dockLiquidFrame(DockLiquidMove.Enter, DockLiquidMove.Enter.durationMs.toFloat(), metrics)
        val (capsule, search) = pieces(last)
        assertEquals(metrics.width - metrics.height - metrics.gap, capsule.end, 0.3f)
        assertEquals(metrics.width - metrics.height, search.start, 0.3f)
        assertEquals(metrics.width, search.end, 0.3f)
        assertEquals(1f, last.glyph)
        assertEquals(0f, last.blend)
        assertEquals(0f, last.dropShift, 0.3f)
        assertEquals(1f, last.dropScale, 0.001f)
    }

    @Test
    fun search_breaks_off_during_the_split_and_does_not_rejoin() {
        val counts =
            (0..DockLiquidMove.Enter.durationMs step 4).map { ms ->
                pieces(dockLiquidFrame(DockLiquidMove.Enter, ms.toFloat(), metrics)).size
            }
        val broken = counts.indexOfFirst { it == 2 }
        assertTrue(broken > 0, "search never broke off: $counts")
        // It breaks after the swell has had time to draw a neck, and stays apart from then on.
        assertTrue(broken * 4 > DOCK_ENTER_LEAD_MS + 200, "broke too early at ${broken * 4} ms")
        assertTrue(counts.drop(broken).all { it == 2 }, "$counts")
    }

    @Test
    fun coming_back_from_collapsed_starts_from_the_collapsed_key_and_search() {
        val first = dockLiquidFrame(DockLiquidMove.Expand, 0f, metrics)
        val (key, search) = pieces(first)
        assertEquals(metrics.height, key.end, 0.1f)
        assertEquals(metrics.width - metrics.height, search.start, 0.1f)
        assertEquals(1f, first.glyph)
    }

    @Test
    fun the_gather_hands_over_to_the_split_without_a_jump() {
        val endOfGather = dockGatherFrame(DOCK_GATHER_MS - 0.01f, metrics)
        val startOfSplit = dockSplitFrame(0f, metrics)
        assertEquals(startOfSplit.capsuleEnd, endOfGather.capsuleEnd, 0.1f)
        assertEquals(startOfSplit.dropCenter, endOfGather.dropCenter, 0.1f)
        assertEquals(startOfSplit.dropRadius, endOfGather.dropRadius, 0.1f)
        assertEquals(startOfSplit.tabSpan, endOfGather.tabSpan, 0.1f)
        assertEquals(startOfSplit.glyph, endOfGather.glyph, 0.01f)
        assertEquals(startOfSplit.blend, endOfGather.blend, 0.5f)
    }

    @Test
    fun the_capsule_sweeps_out_steadily_rather_than_jumping() {
        val ends = (0..DOCK_GATHER_MS step 10).map { dockGatherFrame(it.toFloat(), metrics).capsuleRight }
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // No single 10 ms step crosses more than a fifth of the row.
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b - a < metrics.width / 5f, "$ends") }
    }

    @Test
    fun neither_move_swells_the_dock_out_of_its_height() {
        (0..DockLiquidMove.Expand.durationMs step 5).forEach { ms ->
            val peak = pieces(dockLiquidFrame(DockLiquidMove.Expand, ms.toFloat(), metrics)).maxOf { it.peak }
            assertTrue(peak <= metrics.radius + 1f, "the dock swells to $peak dp at $ms ms")
        }
    }

    @Test
    fun the_expand_ends_at_rest_too() {
        val last = dockLiquidFrame(DockLiquidMove.Expand, DockLiquidMove.Expand.durationMs.toFloat(), metrics)
        val (capsule, search) = pieces(last)
        assertEquals(metrics.width - metrics.height - metrics.gap, capsule.end, 0.3f)
        assertEquals(metrics.width - metrics.height, search.start, 0.3f)
        assertEquals(1f, last.glyph)
    }

    @Test
    fun a_taller_dock_under_large_type_draws_a_proportionally_longer_neck() {
        val large = metrics.copy(height = 76f)
        assertEquals(metrics.viscosity * 76f / 62f, large.viscosity, 0.01f)
    }
}
