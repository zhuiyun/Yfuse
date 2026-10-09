package com.yfuse.app

import com.yfuse.core.designsystem.liquidOutline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DockLiquidTest {
    // A 412 dp phone: the dock row between the two 14 dp margins, 62 dp tall, 14 dp to 搜索.
    private val metrics = DockLiquidMetrics(width = 384f, height = 62f, gap = 14f)

    private fun pieces(frame: DockLiquidFrame) = liquidOutline(frame.bodies(metrics), 0f, metrics.width, 60f)

    private fun frame(
        move: DockLiquidMove,
        ms: Float,
    ) = dockLiquidFrame(move, ms, metrics)

    private fun assertSameFrame(
        expected: DockLiquidFrame,
        actual: DockLiquidFrame,
        tolerance: Float = 0.1f,
        tabs: Boolean = true,
    ) {
        assertEquals(expected.capsuleEnd, actual.capsuleEnd, tolerance)
        assertEquals(expected.dropCenter, actual.dropCenter, tolerance)
        assertEquals(expected.dropRadius, actual.dropRadius, tolerance)
        if (tabs) assertEquals(expected.tabSpan, actual.tabSpan, tolerance)
        assertEquals(expected.glyph, actual.glyph, 0.01f)
        assertEquals(expected.blend, actual.blend, 0.5f)
    }

    @Test
    fun the_dock_rises_as_one_capsule_across_the_whole_row() {
        val first = frame(DockLiquidMove.Enter, 0f)
        val capsule = pieces(first).single()
        assertEquals(0f, capsule.start, 0.1f)
        assertEquals(metrics.width, capsule.end, 0.1f)
        assertEquals(0f, first.glyph)
        assertEquals(metrics.width, first.tabSpan, 0.01f)
    }

    @Test
    fun search_lands_where_the_resting_key_is() {
        val last = frame(DockLiquidMove.Enter, DockLiquidMove.Enter.durationMs.toFloat())
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
                pieces(frame(DockLiquidMove.Enter, ms.toFloat())).size
            }
        val broken = counts.indexOfFirst { it == 2 }
        assertTrue(broken > 0, "search never broke off: $counts")
        // It breaks after the swell has had time to draw a neck, and stays apart from then on.
        assertTrue(broken * 4 > DOCK_ENTER_LEAD_MS + 200, "broke too early at ${broken * 4} ms")
        assertTrue(counts.drop(broken).all { it == 2 }, "$counts")
    }

    @Test
    fun expanding_starts_from_the_collapsed_key_with_search_inside_it() {
        val first = frame(DockLiquidMove.Expand, 0f)
        val key = pieces(first).single()
        assertEquals(0f, key.start, 0.1f)
        assertEquals(metrics.height, key.end, 0.1f)
        assertEquals(0f, first.glyph)
    }

    @Test
    fun search_stays_inside_the_capsule_until_it_has_spread_across_the_row() {
        (0 until DOCK_GATHER_MS step 5).forEach { ms ->
            val gather = frame(DockLiquidMove.Expand, ms.toFloat())
            val capsule = pieces(gather).single()
            assertEquals(gather.capsuleRight, capsule.end, 0.3f, "搜索 shows through the capsule at $ms ms")
            assertEquals(0f, gather.glyph)
        }
    }

    @Test
    fun the_gather_hands_over_to_the_split_without_a_jump() {
        assertSameFrame(dockSplitFrame(0f, metrics), dockGatherFrame(DOCK_GATHER_MS - 0.01f, metrics))
    }

    @Test
    fun the_capsule_sweeps_out_steadily_rather_than_jumping() {
        val ends = (0..DOCK_GATHER_MS step 10).map { dockGatherFrame(it.toFloat(), metrics).capsuleRight }
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        // No single 10 ms step crosses more than a fifth of the row.
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b - a < metrics.width / 5f, "$ends") }
    }

    @Test
    fun no_move_swells_the_dock_out_of_its_height() {
        // The dock grows under 大号文字 while the gap to 搜索 stays 14 dp.
        listOf(metrics, metrics.copy(height = 76f)).forEach { dock ->
            DockLiquidMove.entries.forEach { move ->
                (0..move.durationMs step 5).forEach { ms ->
                    val shape = dockLiquidFrame(move, ms.toFloat(), dock)
                    val peak = liquidOutline(shape.bodies(dock), 0f, dock.width, 80f).maxOf { it.peak }
                    assertTrue(peak <= dock.radius + 1f, "$move swells a ${dock.height} dp dock to $peak dp at $ms ms")
                }
            }
        }
    }

    @Test
    fun the_bridge_is_not_held_back_while_search_is_still_apart() {
        val atRest = dockFlushBlend(metrics.capsuleRest, metrics.searchCenter, metrics.radius, metrics.radius)
        assertTrue(atRest >= metrics.viscosity, "k is capped at $atRest dp before the two meet")
    }

    @Test
    fun a_tucked_drop_allows_no_more_k_than_its_distance_below_the_top() {
        val capsuleEnd = metrics.capsuleFull
        val tucked = 0.55f * metrics.radius
        val flush = dockFlushBlend(capsuleEnd, capsuleEnd - tucked, tucked, metrics.radius)
        assertEquals(metrics.radius - tucked, flush, 0.01f)
    }

    @Test
    fun the_expand_ends_at_rest_too() {
        val last = frame(DockLiquidMove.Expand, DockLiquidMove.Expand.durationMs.toFloat())
        val (capsule, search) = pieces(last)
        assertEquals(metrics.width - metrics.height - metrics.gap, capsule.end, 0.3f)
        assertEquals(metrics.width - metrics.height, search.start, 0.3f)
        assertEquals(1f, last.glyph)
    }

    @Test
    fun collapsing_starts_from_the_dock_at_rest() {
        // The split's springs are within a third of a dp of rest when it hands the dock back.
        val rest = frame(DockLiquidMove.Enter, DockLiquidMove.Enter.durationMs.toFloat())
        assertSameFrame(rest, frame(DockLiquidMove.Collapse, 0f), tolerance = 0.3f)
    }

    @Test
    fun search_joins_the_capsule_before_it_contracts_and_stays_in() {
        val counts =
            (0..DockLiquidMove.Collapse.durationMs step 4).map { ms ->
                pieces(frame(DockLiquidMove.Collapse, ms.toFloat())).size
            }
        assertEquals(2, counts.first())
        val joined = counts.indexOfFirst { it == 1 }
        // Bridged across the gap early in the merge, well before the capsule starts back.
        assertTrue(joined in 1 until DOCK_MERGE_MS / 8, "search joined at ${joined * 4} ms: $counts")
        assertTrue(counts.drop(joined).all { it == 1 }, "$counts")
    }

    @Test
    fun search_stays_sharp_while_it_flows_in_and_fades_only_as_the_capsule_takes_it() {
        (0..DockLiquidMove.Collapse.durationMs step 5).forEach { ms ->
            val collapse = frame(DockLiquidMove.Collapse, ms.toFloat())
            assertEquals(1f, collapse.glyphFocus, "the magnifier blurs at $ms ms")
            if (ms <= DOCK_MERGE_MS / 2) assertEquals(1f, collapse.glyph, "the magnifier fades early, at $ms ms")
        }
        // Gone before the capsule starts back, riding 搜索 in at 搜索's own size until then.
        assertEquals(0f, frame(DockLiquidMove.Collapse, DOCK_MERGE_MS - 20f).glyph)
        val halfway = frame(DockLiquidMove.Collapse, DOCK_MERGE_MS / 2f)
        assertEquals(halfway.dropRadius / metrics.radius, halfway.dropScale, 0.001f)
    }

    @Test
    fun the_tabs_hold_still_while_the_dock_collapses() {
        (0..DockLiquidMove.Collapse.durationMs step 5).forEach { ms ->
            val span = frame(DockLiquidMove.Collapse, ms.toFloat()).tabSpan
            assertEquals(metrics.capsuleRest + metrics.radius, span, 0.01f, "the tabs move at $ms ms")
        }
    }

    @Test
    fun the_merge_hands_over_to_the_contraction_without_a_jump() {
        assertSameFrame(dockContractFrame(0f, metrics), dockMergeFrame(DOCK_MERGE_MS - 0.01f, metrics))
        assertEquals(metrics.width, pieces(dockContractFrame(0f, metrics)).single().end, 0.1f)
    }

    @Test
    fun the_capsule_contracts_steadily_to_the_key() {
        val ends = (0..DOCK_CONTRACT_MS step 10).map { dockContractFrame(it.toFloat(), metrics).capsuleRight }
        ends.zipWithNext().forEach { (a, b) -> assertTrue(b <= a + 0.01f, "$ends") }
        ends.zipWithNext().forEach { (a, b) -> assertTrue(a - b < metrics.width / 5f, "$ends") }
    }

    @Test
    fun the_collapse_ends_as_the_one_key_the_expand_starts_from() {
        val last = frame(DockLiquidMove.Collapse, DockLiquidMove.Collapse.durationMs.toFloat())
        val key = pieces(last).single()
        assertEquals(0f, key.start, 0.1f)
        assertEquals(metrics.height, key.end, 0.1f)
        // The tabs are out of sight at both ends — faded out, not yet faded in — so only the liquid has to meet.
        assertSameFrame(frame(DockLiquidMove.Expand, 0f), last, tabs = false)
    }

    @Test
    fun a_taller_dock_under_large_type_draws_a_proportionally_longer_neck() {
        val large = metrics.copy(height = 76f)
        assertEquals(metrics.viscosity * 76f / 62f, large.viscosity, 0.01f)
    }
}
