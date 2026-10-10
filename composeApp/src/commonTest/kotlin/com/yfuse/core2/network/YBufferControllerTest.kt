package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YBufferControllerTest {
    @Test
    fun `long rebuffer wait drops extra reserve but still requires base buffer`() {
        val gate =
            YPlaybackBufferGate(true, 2_500_000L).also {
                it.updateThresholds(YBufferPlan(8_000_000, 2_500_000, 24 * 1024 * 1024))
            }
        gate.evaluate(500_000L, false)
        gate.markStarved()
        gate.evaluate(2_500_000L, false)
        gate.markStarved()
        assertFalse(gate.evaluate(2_500_000L, false, rebufferWaitUs = 9_000_000L).outputAllowed)
        assertFalse(gate.evaluate(1_000_000L, false, rebufferWaitUs = 10_000_000L).outputAllowed)
        val resumed = gate.evaluate(2_500_000L, false, rebufferWaitUs = 10_000_000L)
        assertTrue(resumed.outputAllowed)
        assertEquals(2_500_000L, resumed.requiredBufferedUs)
    }

    @Test
    fun `healthy playback decays starvation history while pauses do not`() {
        val gate =
            YPlaybackBufferGate(true, 2_500_000L).also {
                it.updateThresholds(YBufferPlan(8_000_000, 2_500_000, 24 * 1024 * 1024))
            }
        gate.evaluate(500_000L, false)
        repeat(3) {
            gate.markStarved()
            gate.evaluate(6_000_000L, false)
        }
        repeat(100) { gate.recordPlaybackProgress(0) }
        gate.markStarved()
        assertEquals(6_000_000L, gate.evaluate(0, false).requiredBufferedUs)
        gate.evaluate(6_000_000L, false)
        repeat(90) { gate.recordPlaybackProgress(1_000_000L) }
        gate.markStarved()
        assertTrue(gate.evaluate(2_500_000L, false).outputAllowed)
    }

    @Test
    fun `repeated starvation increases recovery reserve without delaying startup or deadlocking a full queue`() {
        val plan = YBufferPlan(8_000_000, 2_500_000, 24 * 1024 * 1024)
        val gate = YPlaybackBufferGate(true, plan.resumePlaybackUs).also { it.updateThresholds(plan) }
        assertTrue(gate.evaluate(500_000, false).outputAllowed)
        gate.markStarved()
        assertTrue(gate.evaluate(2_500_000, false).outputAllowed)
        gate.markStarved()
        gate.markStarved() // Repeated observations of one stall are not new episodes.
        assertFalse(gate.evaluate(2_500_000, false).outputAllowed)
        assertTrue(gate.evaluate(5_000_000, false).outputAllowed)
        gate.markStarved()
        assertFalse(gate.evaluate(5_000_000, false).outputAllowed)
        assertTrue(gate.evaluate(6_000_000, false).outputAllowed)
        gate.markStarved()
        assertTrue(gate.evaluate(100_000, false, bufferFull = true).outputAllowed)
        gate.reset()
        assertTrue(gate.evaluate(500_000, false).outputAllowed)
        gate.markStarved()
        assertTrue(gate.evaluate(2_500_000, false).outputAllowed)
    }

    @Test
    fun `five minutes stays on disk and does not delay the first frame`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 20_000_000L,
                    memoryBudgetBytes = 24L * 1024 * 1024,
                    preferredTargetAheadUs = 300_000_000L,
                ),
            )
        assertEquals(300_000_000L, plan.forwardCacheTargetUs)
        assertEquals(500_000L, plan.startupPlaybackUs)
        assertEquals(24L * 1024 * 1024, plan.maximumBytes)
        assertTrue(plan.targetAheadUs <= plan.maximumBytes * 8 * 1_000_000 / 20_000_000)
        assertTrue(plan.resumePlaybackUs > plan.startupPlaybackUs)
    }

    @Test
    fun `double speed accounts for network consumption and media time`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 8_000_000L,
                    measuredNetworkBitsPerSecond = 12_000_000L,
                    preferredTargetAheadUs = 60_000_000L,
                    speed = 2f,
                ),
            )
        assertEquals(120_000_000L, plan.forwardCacheTargetUs)
        assertEquals(1_000_000L, plan.startupPlaybackUs)
        assertEquals(10_000_000L, plan.resumePlaybackUs)
    }

    @Test
    fun `a full variable bitrate queue cannot deadlock rebuffer recovery`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 5_000_000L)
        gate.markStarved()
        assertTrue(gate.evaluate(800_000L, endOfInput = false, bufferFull = true).outputAllowed)
        gate.reset()
        assertFalse(gate.evaluate(100_000L, endOfInput = false).outputAllowed)
        assertEquals(YPlaybackBufferPhase.Startup, gate.phase)
    }

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
                    memoryBudgetBytes = 512L * 1024L * 1024L,
                ),
            )
        val pressured =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 20_000_000L,
                    measuredNetworkBitsPerSecond = 15_000_000L,
                    memoryBudgetBytes = 512L * 1024L * 1024L,
                ),
            )

        assertTrue(pressured.targetAheadUs > healthy.targetAheadUs)
        assertEquals(30_000_000L, healthy.targetAheadUs)
        assertEquals(120_000_000L, pressured.targetAheadUs)
        assertEquals(5_000_000L, pressured.resumePlaybackUs)
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
        assertEquals(30_000_000L, plan.forwardCacheTargetUs)
        assertEquals(2_500_000L, plan.resumePlaybackUs)
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
    fun `one gibibyte high bitrate reserve grows past twenty seconds without delaying startup`() {
        val budget = 1024L * 1024L * 1024L
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 78_938_975L,
                    memoryBudgetBytes = budget,
                    preferredTargetAheadUs = 300_000_000L,
                ),
            )
        assertEquals(budget * 8L * 1_000_000L / 78_938_975L, plan.targetAheadUs)
        assertTrue(plan.targetAheadUs > 100_000_000L)
        assertEquals(500_000L, plan.startupPlaybackUs)
        assertEquals(2_500_000L, plan.resumePlaybackUs)
        assertEquals(300_000_000L, plan.forwardCacheTargetUs)
    }

    @Test
    fun `automatic forward cache keeps five minutes while live startup stays short`() {
        val automatic = YBufferController.plan(YBufferConditions(remote = true))
        assertEquals(300_000_000L, automatic.forwardCacheTargetUs)
        val live = YBufferController.plan(YBufferConditions(remote = true, live = true))
        assertEquals(3_000_000L, live.targetAheadUs)
        assertEquals(500_000L, live.startupPlaybackUs)
    }

    @Test
    fun `large low bitrate memory target stays within the demux time ceiling at higher speed`() {
        val plan =
            YBufferController.plan(
                YBufferConditions(
                    remote = true,
                    mediaBitRateBitsPerSecond = 1_000_000L,
                    memoryBudgetBytes = 1024L * 1024L * 1024L,
                    preferredTargetAheadUs = 300_000_000L,
                    speed = 2f,
                ),
            )
        assertEquals(300_000_000L, plan.targetAheadUs)
        assertEquals(600_000_000L, plan.forwardCacheTargetUs)
        assertEquals(1_000_000L, plan.startupPlaybackUs)
    }

    @Test
    fun `remote startup opens before the rebuffer watermark`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 2_000_000L)

        assertFalse(gate.evaluate(bufferedDurationUs = 499_999L, endOfInput = false).outputAllowed)
        assertTrue(gate.evaluate(bufferedDurationUs = 500_000L, endOfInput = false).outputAllowed)
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
    fun `adaptive resume watermark updates without resetting a healthy gate`() {
        val gate = YPlaybackBufferGate(remote = true, resumePlaybackUs = 2_000_000L)
        assertTrue(gate.evaluate(bufferedDurationUs = 2_000_000L, endOfInput = false).outputAllowed)

        gate.updateResumePlaybackUs(4_000_000L)
        assertEquals(YPlaybackBufferPhase.Ready, gate.phase)
        gate.markStarved()

        assertFalse(gate.evaluate(bufferedDurationUs = 3_999_999L, endOfInput = false).outputAllowed)
        assertTrue(gate.evaluate(bufferedDurationUs = 4_000_000L, endOfInput = false).outputAllowed)
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
