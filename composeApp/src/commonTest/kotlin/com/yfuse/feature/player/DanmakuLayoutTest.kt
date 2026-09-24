package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuKind
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
}
