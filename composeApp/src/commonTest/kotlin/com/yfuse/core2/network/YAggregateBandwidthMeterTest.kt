package com.yfuse.core2.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YAggregateBandwidthMeterTest {
    @Test
    fun `overlapping transfers are measured as one link, not four`() {
        val meter = YAggregateBandwidthMeter()
        // Four 2 MiB ranges, all in flight over the same one second of wall clock.
        repeat(4) { meter.onTransferStarted(0L) }
        repeat(3) { meter.onTransferFinished(TWO_MIB, ONE_SECOND_NS) }
        val sample = meter.onTransferFinished(TWO_MIB, ONE_SECOND_NS)

        assertNotNull(sample)
        // Timing each range on its own clock would have reported 16 Mbps - one connection's share.
        assertEquals(8L * 4L * TWO_MIB, sample.bitsPerSecond)
    }

    @Test
    fun `a busy period stays open while any transfer is in flight`() {
        val meter = YAggregateBandwidthMeter()
        meter.onTransferStarted(0L)
        meter.onTransferStarted(0L)

        assertNull(meter.onTransferFinished(TWO_MIB, ONE_SECOND_NS / 2L))
        assertNotNull(meter.onTransferFinished(TWO_MIB, ONE_SECOND_NS))
    }

    @Test
    fun `idle time between busy periods is not counted against throughput`() {
        val meter = YAggregateBandwidthMeter()
        meter.onTransferStarted(0L)
        meter.onTransferFinished(TWO_MIB, ONE_SECOND_NS)
        // Ten seconds of idle, then another second of transfer.
        meter.onTransferStarted(11L * ONE_SECOND_NS)
        val sample = meter.onTransferFinished(TWO_MIB, 12L * ONE_SECOND_NS)

        assertNotNull(sample)
        assertEquals(8L * TWO_MIB, sample.bitsPerSecond)
    }

    @Test
    fun `one burst cannot move the estimate the way an average would`() {
        val meter = YAggregateBandwidthMeter()
        var nowNs = 0L
        repeat(12) {
            meter.onTransferStarted(nowNs)
            nowNs += ONE_SECOND_NS
            meter.onTransferFinished(TWO_MIB, nowNs)
        }
        val steady = meter.bitsPerSecond()

        // A single CDN burst that delivers the same block a hundred times faster.
        meter.onTransferStarted(nowNs)
        nowNs += ONE_SECOND_NS / 100L
        meter.onTransferFinished(TWO_MIB, nowNs)

        assertEquals(steady, meter.bitsPerSecond())
    }

    @Test
    fun `the estimate follows a sustained change`() {
        val meter = YAggregateBandwidthMeter()
        var nowNs = 0L
        repeat(8) {
            meter.onTransferStarted(nowNs)
            nowNs += ONE_SECOND_NS
            meter.onTransferFinished(TWO_MIB, nowNs)
        }
        val before = meter.bitsPerSecond()
        repeat(24) {
            meter.onTransferStarted(nowNs)
            nowNs += 4L * ONE_SECOND_NS
            meter.onTransferFinished(TWO_MIB, nowNs)
        }

        assertTrue(meter.bitsPerSecond() < before, "${meter.bitsPerSecond()} !< $before")
    }

    @Test
    fun `a transfer too small to time is not recorded`() {
        val meter = YAggregateBandwidthMeter()
        meter.onTransferStarted(0L)

        assertNull(meter.onTransferFinished(bytes = 1_024L, nowNs = ONE_SECOND_NS))
        assertEquals(0L, meter.bitsPerSecond())
    }

    @Test
    fun `a failed transfer still closes its busy period`() {
        val meter = YAggregateBandwidthMeter()
        meter.onTransferStarted(0L)
        assertNull(meter.onTransferFinished(bytes = 0L, nowNs = ONE_SECOND_NS))

        meter.onTransferStarted(2L * ONE_SECOND_NS)
        val sample = meter.onTransferFinished(TWO_MIB, 3L * ONE_SECOND_NS)

        assertNotNull(sample)
        assertEquals(8L * TWO_MIB, sample.bitsPerSecond)
    }

    private companion object {
        const val TWO_MIB = 2L * 1024L * 1024L
        const val ONE_SECOND_NS = 1_000_000_000L
    }
}
