package com.yfuse.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuPickTest {
    private fun placement(
        index: Int,
        timeMs: Long,
        lane: Int,
        width: Float = 100f,
        kind: DanmakuKind = DanmakuKind.Scroll,
        text: String = "弹幕$index",
    ) = DanmakuLanePlacement(DanmakuLayoutInput(index, DanmakuComment(timeMs, text, kind = kind), width), lane)

    // A 900-wide area of 30-high lanes; scrolling comments take 9 s, fixed ones 4 s.
    private fun layout(vararg placements: DanmakuLanePlacement) =
        DanmakuPickLayout(
            placements = placements.toList(),
            laneHeight = 30f,
            viewportWidth = 900f,
            scrollDurationMs = 9_000L,
            fixedDurationMs = 4_000L,
        )

    @Test
    fun a_scrolling_comment_crosses_from_the_right_edge_to_past_the_left() {
        assertEquals(900f, danmakuLeft(DanmakuKind.Scroll, 0L, 9_000L, 900f, 100f))
        assertEquals(400f, danmakuLeft(DanmakuKind.Scroll, 4_500L, 9_000L, 900f, 100f))
        assertEquals(-100f, danmakuLeft(DanmakuKind.Scroll, 9_000L, 9_000L, 900f, 100f))
        // Fixed comments sit in the middle for as long as they last.
        assertEquals(400f, danmakuLeft(DanmakuKind.Top, 1_000L, 4_000L, 900f, 100f))
    }

    @Test
    fun a_finger_finds_the_comment_where_it_is_drawn_now() {
        // Halfway through, comment 0 spans 400..500 in lane 1 (30..60).
        val found = assertNotNull(layout(placement(0, 0L, lane = 1)).pick(Offset(450f, 45f), 4_500L, 10f))
        assertEquals(0, found.index)
        assertEquals(4_500L, found.heldElapsedMs)
        assertEquals(1, found.lane)
        // Where it was a second ago is no longer it.
        assertNull(layout(placement(0, 0L, lane = 1)).pick(Offset(550f, 45f), 4_500L, 10f))
    }

    @Test
    fun a_near_miss_within_the_slop_still_lands_and_a_wider_one_does_not() {
        val area = layout(placement(0, 0L, lane = 1))
        assertNotNull(area.pick(Offset(508f, 45f), 4_500L, 10f))
        assertNotNull(area.pick(Offset(450f, 66f), 4_500L, 10f))
        assertNull(area.pick(Offset(515f, 45f), 4_500L, 10f))
        assertNull(area.pick(Offset(450f, 75f), 4_500L, 10f))
    }

    @Test
    fun comments_not_yet_arrived_or_already_gone_are_not_there() {
        val area = layout(placement(0, 5_000L, lane = 0), placement(1, 0L, lane = 0, kind = DanmakuKind.Top))
        // Comment 0 starts at 5 s; comment 1 left at 4 s.
        assertNull(area.pick(Offset(899f, 15f), 4_500L, 10f))
        assertNull(area.pick(Offset(450f, 15f), 4_500L, 10f))
    }

    @Test
    fun between_two_comments_the_one_the_finger_is_inside_wins() {
        // In lane 0: comment 0 spans 400..500 and comment 1 (started 1.8 s later) spans 600..700.
        val area = layout(placement(0, 0L, lane = 0), placement(1, 1_800L, lane = 0))
        assertEquals(1, area.pick(Offset(605f, 15f), 4_500L, 10f)?.index)
        assertEquals(0, area.pick(Offset(495f, 15f), 4_500L, 10f)?.index)
        assertEquals(0, area.pick(Offset(505f, 15f), 4_500L, 10f)?.index)
        // Two lanes' comments both within the slop of a finger on the line between them.
        val stacked = layout(placement(2, 0L, lane = 0), placement(3, 0L, lane = 1))
        assertEquals(2, stacked.pick(Offset(450f, 28f), 4_500L, 10f)?.index)
        assertEquals(3, stacked.pick(Offset(450f, 32f), 4_500L, 10f)?.index)
    }

    @Test
    fun a_press_is_claimed_only_by_the_tap_that_follows_it() {
        var clock = 4_500L
        val state = DanmakuPickState()
        state.layout = layout(placement(0, 0L, lane = 1))
        state.clock = { clock }
        state.press(Offset(450f, 45f))
        // The tap is confirmed a double-tap window later; the comment has flown on by then.
        clock = 4_800L
        assertTrue(state.claim(Offset(452f, 46f)))
        val hold = assertNotNull(state.hold)
        assertTrue(state.menuOpen)
        assertEquals(4_800L, hold.heldElapsedMs)
        // It stays where it stopped however long the menu is open.
        clock = 9_000L
        assertEquals(hold.leftAt(4_800L), assertNotNull(state.hold).leftAt(clock))
        // A second tap with no press behind it claims nothing.
        assertFalse(state.claim(Offset(452f, 46f)))
    }

    @Test
    fun a_tap_that_went_down_on_nothing_or_ended_elsewhere_is_left_alone() {
        val state = DanmakuPickState()
        state.layout = layout(placement(0, 0L, lane = 1))
        state.clock = { 4_500L }
        state.press(Offset(100f, 45f))
        assertFalse(state.claim(Offset(100f, 45f)))
        state.press(Offset(450f, 45f))
        assertFalse(state.claim(Offset(450f + DANMAKU_CLAIM_SLOP + 1f, 45f)))
        assertNull(state.hold)
    }

    @Test
    fun a_comment_gone_by_the_time_the_tap_is_confirmed_is_not_claimed() {
        var clock = 8_990L
        val state = DanmakuPickState()
        state.layout = layout(placement(0, 0L, lane = 0))
        state.clock = { clock }
        state.press(Offset(-85f, 15f))
        clock = 9_300L
        assertFalse(state.claim(Offset(-85f, 15f)))
    }

    @Test
    fun a_released_comment_sets_off_from_where_it_stopped_and_is_let_go_once_off_screen() {
        var clock = 4_500L
        val state = DanmakuPickState()
        state.layout = layout(placement(0, 0L, lane = 1))
        state.clock = { clock }
        state.press(Offset(450f, 45f))
        assertTrue(state.claim(Offset(450f, 45f)))
        clock = 20_000L
        state.release()
        val released = assertNotNull(state.hold)
        assertFalse(released.held)
        // No jump: at the moment it sets off it is exactly where it was held.
        assertEquals(400f, released.leftAt(20_000L))
        assertEquals(danmakuLeft(DanmakuKind.Scroll, 5_500L, 9_000L, 900f, 100f), released.leftAt(21_000L))
        state.settle(24_000L)
        assertNotNull(state.hold)
        state.settle(24_600L)
        assertNull(state.hold)
    }

    @Test
    fun seeking_back_past_the_release_lets_the_comment_go() {
        val hold =
            DanmakuHold(0, DanmakuComment(0L, "x"), 0, 100f, 30f, 900f, 9_000L, 4_500L, releasedAtMs = 20_000L)
        assertTrue(hold.finishedAt(19_000L))
        assertFalse(hold.finishedAt(20_500L))
        assertFalse(hold.copy(releasedAtMs = null).finishedAt(0L))
    }

    @Test
    fun the_menu_goes_under_the_comment_or_over_it_and_never_off_the_sides() {
        val bounds = Size(900f, 400f)
        val menu = Size(200f, 40f)
        assertEquals(
            Offset(300f, 68f),
            danmakuMenuPosition(Rect(300f, 30f, 400f, 60f), menu, bounds, gap = 8f, margin = 16f),
        )
        // Too low for the menu to fit under: it goes over.
        assertEquals(
            Offset(300f, 302f),
            danmakuMenuPosition(Rect(300f, 350f, 400f, 380f), menu, bounds, gap = 8f, margin = 16f),
        )
        // A comment half off the left edge, and one against the right.
        assertEquals(16f, danmakuMenuPosition(Rect(-60f, 0f, 40f, 30f), menu, bounds, 8f, 16f).x)
        assertEquals(684f, danmakuMenuPosition(Rect(850f, 0f, 950f, 30f), menu, bounds, 8f, 16f).x)
    }

    @Test
    fun a_hold_matches_its_own_placement_only() {
        val area = layout(placement(3, 0L, lane = 1), placement(4, 0L, lane = 2))
        val hold = assertNotNull(area.pick(Offset(450f, 45f), 4_500L, 10f))
        assertTrue(hold.isFor(area.placements[0]))
        assertFalse(hold.isFor(area.placements[1]))
    }
}
