package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuKind
import com.yfuse.core.designsystem.Motion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuLayoutTest {
    @Test
    fun lower_bound_uses_sorted_comment_times() {
        val comments = listOf(100L, 500L, 500L, 900L).map { DanmakuComment(it, "$it") }

        assertEquals(0, lowerBoundDanmaku(comments, 0L))
        assertEquals(1, lowerBoundDanmaku(comments, 500L))
        assertEquals(3, lowerBoundDanmaku(comments, 501L))
        assertEquals(4, lowerBoundDanmaku(comments, 1_000L))
    }

    @Test
    fun reduce_motion_holds_scrolling_comments_still_and_leaves_fixed_ones_alone() {
        val flying = DanmakuComment(0L, "飞过", kind = DanmakuKind.Scroll)
        assertEquals(DanmakuKind.Top, flying.heldStill(reduceMotion = true).kind)
        assertEquals(flying, flying.heldStill(reduceMotion = false))
        val bottom = DanmakuComment(0L, "底部", kind = DanmakuKind.Bottom)
        assertEquals(bottom, bottom.heldStill(reduceMotion = true))
    }

    @Test
    fun a_held_comment_fades_in_and_out_instead_of_cutting() {
        assertEquals(0f, danmakuHeldAlpha(elapsedMs = -1L, durationMs = 4_000L, fadeMs = 150L))
        assertEquals(0.5f, danmakuHeldAlpha(elapsedMs = 75L, durationMs = 4_000L, fadeMs = 150L))
        assertEquals(1f, danmakuHeldAlpha(elapsedMs = 2_000L, durationMs = 4_000L, fadeMs = 150L))
        assertEquals(0.5f, danmakuHeldAlpha(elapsedMs = 3_925L, durationMs = 4_000L, fadeMs = 150L))
        assertEquals(0f, danmakuHeldAlpha(elapsedMs = 4_001L, durationMs = 4_000L, fadeMs = 150L))
    }

    @Test
    fun a_flying_comment_is_drawn_only_while_it_crosses_and_fades_when_held_still() {
        assertEquals(0f, danmakuDrawAlpha(elapsedMs = -1L, durationMs = 8_000L, reduceMotion = false))
        assertEquals(1f, danmakuDrawAlpha(elapsedMs = 0L, durationMs = 8_000L, reduceMotion = false))
        assertEquals(1f, danmakuDrawAlpha(elapsedMs = 8_000L, durationMs = 8_000L, reduceMotion = false))
        assertEquals(0f, danmakuDrawAlpha(elapsedMs = 8_001L, durationMs = 8_000L, reduceMotion = false))
        assertEquals(
            danmakuHeldAlpha(elapsedMs = 75L, durationMs = 4_000L, fadeMs = Motion.REDUCED_FADE.toLong()),
            danmakuDrawAlpha(elapsedMs = 75L, durationMs = 4_000L, reduceMotion = true),
        )
    }

    @Test
    fun a_lane_takes_no_more_comments_at_once_than_its_cap() {
        // Lines this narrow clear the screen's edge in under 80 ms: only the cap keeps a lane from filling.
        val narrow =
            listOf(0L, 100L, 200L, 300L, 400L, 8_000L).mapIndexed { index, timeMs ->
                DanmakuLayoutInput(index = index, comment = DanmakuComment(timeMs, "$index"), width = 10f)
            }

        val oneLane =
            allocateDanmakuLanes(
                inputs = narrow,
                laneCount = 1,
                viewportWidth = 1_000f,
                scrollDurationMs = 8_000L,
                maxPerLane = 3,
            )
        // The fourth and fifth find the lane full; by 8 s the first has gone and makes room.
        assertEquals(listOf(0, 1, 2, 5), oneLane.map { it.input.index })

        val twoLanes =
            allocateDanmakuLanes(
                inputs = narrow,
                laneCount = 2,
                viewportWidth = 1_000f,
                scrollDurationMs = 8_000L,
                maxPerLane = 3,
            )
        assertEquals(listOf(0, 0, 0, 1, 1, 0), twoLanes.map { it.lane })
    }

    @Test
    fun a_comment_dropped_at_a_full_lane_stays_dropped() {
        val cache = HashMap<DanmakuKey, Int>()
        val narrow = (0 until 3).map { DanmakuLayoutInput(it, DanmakuComment(it * 100L, "$it"), width = 10f) }
        val first =
            allocateDanmakuLanes(narrow, 1, 1_000f, 8_000L, laneCache = cache, maxPerLane = 2)
        assertEquals(listOf(0, 1), first.map { it.input.index })
        // A rebuilt list without the first leaves room, but the third would appear mid-flight.
        val rebuilt =
            allocateDanmakuLanes(narrow.drop(1), 1, 1_000f, 8_000L, laneCache = cache, maxPerLane = 2)
        assertEquals(listOf(1), rebuilt.map { it.input.index })
    }

    @Test
    fun dense_comments_are_dropped_when_the_only_lane_is_not_clear() {
        val placements =
            allocateDanmakuLanes(
                inputs =
                    listOf(
                        input(0, 0L),
                        input(1, 100L),
                        input(2, 1_000L),
                    ),
                laneCount = 1,
                viewportWidth = 1_000f,
                scrollDurationMs = 8_000L,
            )

        assertEquals(listOf(0, 2), placements.map { it.input.index })
    }

    @Test
    fun fixed_comment_reserves_its_lane_for_the_full_display_duration() {
        val placements =
            allocateDanmakuLanes(
                inputs =
                    listOf(
                        input(0, 0L, DanmakuKind.Top),
                        input(1, 3_999L, DanmakuKind.Scroll),
                        input(2, 4_000L, DanmakuKind.Scroll),
                    ),
                laneCount = 1,
                viewportWidth = 1_000f,
                scrollDurationMs = 8_000L,
            )

        assertEquals(listOf(0, 2), placements.map { it.input.index })
    }

    @Test
    fun a_comment_keeps_its_lane_when_the_list_around_it_changes() {
        val cache = HashMap<DanmakuKey, Int>()
        val first = cache.allocate(3, line(0, 0L, "甲"), line(1, 100L, "乙"), line(2, 200L, "丙"))
        assertEquals(listOf(0, 1, 2), first.map { it.lane })
        // 屏蔽 takes 甲 out and moves everything after it up an index; 乙 and 丙 stay where they fly.
        val blocked = cache.allocate(3, line(0, 100L, "乙"), line(1, 200L, "丙"))
        assertEquals(listOf("乙" to 1, "丙" to 2), blocked.map { it.input.comment.text to it.lane })
    }

    @Test
    fun a_comment_dropped_for_want_of_room_stays_dropped_when_room_opens_up() {
        val cache = HashMap<DanmakuKey, Int>()
        val first = cache.allocate(1, line(0, 0L, "甲"), line(1, 100L, "乙"))
        assertEquals(listOf("甲"), first.map { it.input.comment.text })
        // With 甲 blocked its lane is free, but 乙 would appear halfway across the screen.
        assertEquals(emptyList(), cache.allocate(1, line(0, 100L, "乙")))
    }

    @Test
    fun a_comment_let_in_among_placed_ones_keeps_clear_of_the_next_one_in_its_lane() {
        val cache = HashMap<DanmakuKey, Int>()
        val placed = cache.allocate(2, line(0, 0L, "甲"), line(1, 1_000L, "丙"))
        assertEquals(listOf(0, 0), placed.map { it.lane })
        // 乙 clears 甲, but 丙 would run into it: it takes the other lane, and 丙 stays put.
        val admitted = cache.allocate(2, line(0, 0L, "甲"), line(1, 800L, "乙"), line(2, 1_000L, "丙"))
        assertEquals(listOf("甲" to 0, "乙" to 1, "丙" to 0), admitted.map { it.input.comment.text to it.lane })
    }

    @Test
    fun identical_lines_at_one_moment_are_told_apart_and_found_again() {
        val comments =
            listOf(
                DanmakuComment(0L, "嗯"),
                DanmakuComment(500L, "哈"),
                DanmakuComment(500L, "哈"),
                DanmakuComment(500L, "嗯"),
            )
        assertEquals(listOf(0, 0, 1, 0), danmakuKeysIn(comments, 0, comments.size).map { it.ordinal })
        // A window that starts partway through a moment counts from the moment's first comment.
        val second = danmakuKeysIn(comments, 2, 3).single()
        assertEquals(1, second.ordinal)
        assertTrue(comments.containsDanmaku(second))
        assertFalse(listOf(DanmakuComment(500L, "哈"), DanmakuComment(500L, "嗯")).containsDanmaku(second))
    }

    @Test
    fun recovery_fence_stays_armed_on_the_pre_retry_position_sample() {
        val armed = armDanmakuRecoveryFence(renderedPositionMs = 20_000L, reportedPositionMs = 20_000L)

        val update =
            updateDanmakuRecoveryFence(
                state = armed,
                reportedPositionMs = 20_000L,
                renderedPositionMs = 20_000L,
            )

        assertTrue(update.state.active)
        assertEquals(20_000L, update.state.floorMs)
        assertFalse(update.state.rollbackObserved)
        assertNull(update.holdAtMs)
        assertNull(update.resumeAtMs)
    }

    @Test
    fun recovery_fence_holds_the_highest_danmaku_position_when_backend_rolls_back() {
        val armed = armDanmakuRecoveryFence(renderedPositionMs = 20_000L, reportedPositionMs = 20_000L)

        val update =
            updateDanmakuRecoveryFence(
                state = armed,
                reportedPositionMs = 16_000L,
                renderedPositionMs = 20_500L,
            )

        assertTrue(update.state.active)
        assertTrue(update.state.rollbackObserved)
        assertEquals(20_500L, update.state.floorMs)
        assertEquals(20_500L, update.holdAtMs)
        assertNull(update.resumeAtMs)
    }

    @Test
    fun recovery_fence_releases_only_after_media_catches_the_consumed_high_water() {
        val held = DanmakuRecoveryFenceState(floorMs = 20_500L, rollbackObserved = true)

        val update =
            updateDanmakuRecoveryFence(
                state = held,
                reportedPositionMs = 20_600L,
                renderedPositionMs = 20_500L,
            )

        assertFalse(update.state.active)
        assertNull(update.holdAtMs)
        assertEquals(20_600L, update.resumeAtMs)
    }

    @Test
    fun recovery_fence_retires_after_forward_progress_when_retry_never_rolls_back() {
        val armed = armDanmakuRecoveryFence(renderedPositionMs = 20_000L, reportedPositionMs = 20_000L)

        val update =
            updateDanmakuRecoveryFence(
                state = armed,
                reportedPositionMs = 21_100L,
                renderedPositionMs = 21_100L,
            )

        assertFalse(update.state.active)
        assertNull(update.holdAtMs)
        assertNull(update.resumeAtMs)
    }

    @Test
    fun stagnant_media_clock_freezes_danmaku_lead_without_rewinding_it() {
        val atLeadLimit =
            advanceDanmakuInterpolatedPosition(
                renderedPositionMs = 21_500L,
                reportedPositionMs = 20_000L,
                elapsedMs = 16L,
                playbackRate = 1f,
            )

        assertEquals(21_500L, atLeadLimit)
        assertFalse(isDanmakuBackwardSeek(20_000L, 20_000L))
        assertFalse(isDanmakuBackwardSeek(20_000L, 20_500L))
    }

    @Test
    fun deliberate_user_backward_seek_still_resets_danmaku() {
        assertTrue(isDanmakuBackwardSeek(20_000L, 10_000L))
    }

    private fun input(
        index: Int,
        timeMs: Long,
        kind: DanmakuKind = DanmakuKind.Scroll,
    ) = DanmakuLayoutInput(
        index = index,
        comment = DanmakuComment(timeMs, "弹幕 $index", kind = kind),
        width = 100f,
    )

    /** A comment that stays itself whatever index a rebuilt list gives it. */
    private fun line(
        index: Int,
        timeMs: Long,
        text: String,
    ) = DanmakuLayoutInput(index = index, comment = DanmakuComment(timeMs, text), width = 100f)

    /** One window's allocation on a 1000-wide screen with 8 s scrolls, remembering lanes in this cache. */
    private fun HashMap<DanmakuKey, Int>.allocate(
        laneCount: Int,
        vararg lines: DanmakuLayoutInput,
    ) = allocateDanmakuLanes(
        inputs = lines.toList(),
        laneCount = laneCount,
        viewportWidth = 1_000f,
        scrollDurationMs = 8_000L,
        laneCache = this,
    )
}
