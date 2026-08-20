package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YBufferControllerTest {
    @Test
    fun `keeps local playback latency low`() {
        val plan = YBufferController.plan(YBufferConditions(remote = false))

        assertEquals(1_500_000L, plan.targetAheadUs)
        assertEquals(500_000L, plan.resumePlaybackUs)
    }

    @Test
    fun `grows remote target under throughput pressure`() {
        val healthy =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 20_000_000L,
                    measuredNetworkBitsPerSecond = 40_000_000L,
                ),
            )
        val pressured =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 20_000_000L,
                    measuredNetworkBitsPerSecond = 15_000_000L,
                ),
            )

        assertTrue(pressured.targetAheadUs > healthy.targetAheadUs)
    }

    @Test
    fun `caps high bitrate remux buffering by memory budget`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 150_000_000L,
                    memoryBudgetBytes = 64L * 1024L * 1024L,
                ),
            )

        assertTrue(plan.targetAheadUs in 3_500_000L..3_600_000L)
    }
}
