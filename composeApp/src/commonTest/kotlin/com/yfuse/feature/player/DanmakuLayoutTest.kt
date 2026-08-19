package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment
import com.yfuse.core.data.DanmakuKind
import kotlin.test.Test
import kotlin.test.assertEquals

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
