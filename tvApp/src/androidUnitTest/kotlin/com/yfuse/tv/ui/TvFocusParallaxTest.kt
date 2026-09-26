package com.yfuse.tv.ui

import com.yfuse.core.designsystem.ItemAction
import com.yfuse.core.designsystem.LiftMenu
import com.yfuse.tv.focus.TvFocusDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvFocusParallaxTest {
    @Test
    fun `a fresh press is the direction focus arrived from`() {
        val travel = TvFocusTravel()
        travel.press(TvFocusDirection.Right, repeated = false, atMs = 1_000L)

        assertEquals(TvFocusDirection.Right, travel.arrival(nowMs = 1_000L))
        assertEquals(TvFocusDirection.Right, travel.arrival(nowMs = 1_000L + TvFocusMotion.ARRIVAL_WINDOW_MILLIS))
    }

    @Test
    fun `focus arriving long after the press, or with no press, arrives still`() {
        val travel = TvFocusTravel()
        assertNull(travel.arrival(nowMs = 50L))

        travel.press(TvFocusDirection.Left, repeated = false, atMs = 1_000L)
        assertNull(travel.arrival(nowMs = 1_001L + TvFocusMotion.ARRIVAL_WINDOW_MILLIS))
        // A clock that reads earlier than the press is not this press's arrival either.
        assertNull(travel.arrival(nowMs = 999L))
    }

    @Test
    fun `a held D-pad racing along a row turns nothing`() {
        val travel = TvFocusTravel()
        travel.press(TvFocusDirection.Right, repeated = false, atMs = 0L)
        travel.press(TvFocusDirection.Right, repeated = true, atMs = 50L)

        assertNull(travel.arrival(nowMs = 60L))

        travel.press(TvFocusDirection.Down, repeated = false, atMs = 900L)
        assertEquals(TvFocusDirection.Down, travel.arrival(nowMs = 910L))
    }

    @Test
    fun `the side focus came from starts set back`() {
        // A positive turn sets the right edge back, so arriving from the left turns negative.
        assertEquals(-TvFocusMotion.PARALLAX_DEGREES, tvParallaxStartDegrees(TvFocusDirection.Right))
        assertEquals(TvFocusMotion.PARALLAX_DEGREES, tvParallaxStartDegrees(TvFocusDirection.Left))
        assertEquals(0f, tvParallaxStartDegrees(TvFocusDirection.Up))
        assertEquals(0f, tvParallaxStartDegrees(TvFocusDirection.Down))
        assertEquals(8f, TvFocusMotion.PARALLAX_DEGREES)
    }

    @Test
    fun `the light starts wholly off the left edge and ends wholly past the right`() {
        val width = 142f
        val band = width * TvFocusMotion.SWEEP_BAND
        val lean = 30f

        val start = tvSweepCentre(progress = 0f, width = width, band = band, lean = lean)
        val end = tvSweepCentre(progress = 1f, width = width, band = band, lean = lean)
        // The band's right edge at its top is half a band and a lean ahead of its centre line.
        assertTrue(start + band / 2f + lean <= EDGE_TOLERANCE)
        assertTrue(end - band / 2f - lean >= width - EDGE_TOLERANCE)
        assertEquals(width / 2f, tvSweepCentre(progress = 0.5f, width = width, band = band, lean = lean), 0.001f)
    }

    @Test
    fun `the panel ends with 查看详情 when the title opens`() {
        var opened = 0
        val play = ItemAction(label = "播放", leavesPage = true, onSelect = {})
        val favourite = ItemAction(label = "收藏", onSelect = {})
        val menu =
            LiftMenu(
                title = "深海回声",
                onOpen = { opened++ },
                sections = listOf(listOf(play), emptyList(), listOf(favourite)),
            )

        val sections = tvQuickActionSections(menu)

        assertEquals(listOf(listOf("播放"), listOf("收藏"), listOf("查看详情")), sections.map { it.map(ItemAction::label) })
        val detail = sections.last().single()
        assertTrue(detail.leavesPage)
        detail.onSelect()
        assertEquals(1, opened)
    }

    @Test
    fun `a title with nothing to open keeps only its own rows`() {
        val menu = LiftMenu(title = "雾港", sections = listOf(listOf(ItemAction(label = "收藏", onSelect = {}))))

        assertEquals(listOf(listOf("收藏")), tvQuickActionSections(menu).map { it.map(ItemAction::label) })
    }

    private companion object {
        /** The band just touches the edge at each end; float rounding may leave it a hair inside. */
        const val EDGE_TOLERANCE = 0.001f
    }
}
