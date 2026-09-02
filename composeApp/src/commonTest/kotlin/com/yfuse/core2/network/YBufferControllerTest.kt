package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `manual remote target overrides automatic network policy`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 8_000_000L,
                    measuredNetworkBitsPerSecond = 80_000_000L,
                    preferredTargetAheadUs = 30_000_000L,
                ),
            )

        assertEquals(30_000_000L, plan.targetAheadUs)
        assertEquals(15_000_000L, plan.resumePlaybackUs)
    }

    @Test
    fun `manual remote target still respects the memory budget`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 150_000_000L,
                    memoryBudgetBytes = 64L * 1024L * 1024L,
                    preferredTargetAheadUs = 30_000_000L,
                ),
            )

        assertTrue(plan.targetAheadUs in 3_500_000L..3_600_000L)
    }

    @Test
    fun `remote startup waits for resume watermark`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 2_000_000L)

        assertFalse(gate.evaluate(bufferedDurationUs = 1_999_999L, endOfInput = false).outputAllowed)
        assertTrue(gate.evaluate(bufferedDurationUs = 2_000_000L, endOfInput = false).outputAllowed)
        assertEquals(YPlaybackBufferPhase.Ready, gate.phase)
    }

    @Test
    fun `remote starvation pauses until buffer is rebuilt`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 2_000_000L)
        assertTrue(gate.evaluate(bufferedDurationUs = 2_000_000L, endOfInput = false).outputAllowed)

        gate.markStarved()

        assertFalse(gate.evaluate(bufferedDurationUs = 500_000L, endOfInput = false).outputAllowed)
        assertTrue(gate.evaluate(bufferedDurationUs = 2_100_000L, endOfInput = false).outputAllowed)
    }

    @Test
    fun `short remote input opens gate at end of input`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 4_000_000L)

        assertTrue(gate.evaluate(bufferedDurationUs = 250_000L, endOfInput = true).outputAllowed)
    }

    @Test
    fun `local input never waits for the buffer gate`() {
        val gate = YPlaybackBufferGate(remote = false, resumePlaybackUs = 500_000L)

        gate.markStarved()

        assertTrue(gate.evaluate(bufferedDurationUs = 0L, endOfInput = false).outputAllowed)
    }
}
