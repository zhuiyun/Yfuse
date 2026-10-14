package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDirectPacingMetricsTest {
    @Test
    fun untrustedRenderBreaksGapMeasurementButStillCountsFrame() {
        val metrics = NativeDirectPacingMetrics()
        metrics.recordRenderedFrame(1_000_000_000L, true)
        metrics.recordRenderedFrame(1_100_000_000L, true)
        metrics.recordRenderedFrame(8_000_000_000L, false)
        metrics.recordRenderedFrame(9_000_000_000L, true)

        assertEquals(4L, metrics.renderedFrameCount)
        assertEquals(1, metrics.longRenderGapCount)
        assertEquals(100_000_000L, metrics.maximumRenderGapNs)
    }

    @Test
    fun seekResetsAnchorWithoutErasingSessionCounters() {
        val metrics = NativeDirectPacingMetrics()
        metrics.recordRenderedFrame(1_000_000_000L, true)
        metrics.recordRenderedFrame(1_150_000_000L, true)
        metrics.resetRenderAnchor()
        metrics.recordRenderedFrame(5_000_000_000L, true)

        assertEquals(3L, metrics.renderedFrameCount)
        assertEquals(1, metrics.longRenderGapCount)
        assertEquals(150_000_000L, metrics.maximumRenderGapNs)
    }

    @Test
    fun runtimeRecoveryRetainsPumpStallEvidenceAndResetsMediaCounters() {
        val metrics = NativeDirectPacingMetrics()
        metrics.recordPump(19_999_999L)
        metrics.recordPump(20_000_000L)
        metrics.recordPump(80_000_000L)
        metrics.recordAudioBackpressure()
        metrics.recordRenderedFrame(1_000_000_000L, true)
        metrics.recordRenderedFrame(1_200_000_000L, true)
        assertTrue(metrics.beginPublication(10_000_000_000L))

        metrics.resetMedia()

        assertEquals(2, metrics.slowPumpCount)
        assertEquals(80_000_000L, metrics.maximumPumpDurationNs)
        assertEquals(0, metrics.audioBackpressureCount)
        assertEquals(0L, metrics.renderedFrameCount)
        assertEquals(0, metrics.longRenderGapCount)
        assertEquals(0L, metrics.maximumRenderGapNs)
        assertTrue(metrics.beginPublication(10_000_000_001L))
        metrics.recordRenderedFrame(20_000_000_000L, true)
        assertEquals(0, metrics.longRenderGapCount)
    }

    @Test
    fun publicationThrottlesAtBoundaryAndOnlyClearsWindowMaximum() {
        val metrics = NativeDirectPacingMetrics()
        metrics.recordPump(60_000_000L)
        assertFalse(metrics.beginPublication(4_999_999_999L))
        assertTrue(metrics.beginPublication(5_000_000_000L))
        assertEquals(60_000_000L, metrics.maximumPumpDurationNs)

        metrics.finishPublication()

        assertEquals(0L, metrics.maximumPumpDurationNs)
        assertEquals(1, metrics.slowPumpCount)
        assertFalse(metrics.beginPublication(9_999_999_999L))
        assertTrue(metrics.beginPublication(10_000_000_000L))
        metrics.recordPump(10_000_000L)
        assertEquals(10_000_000L, metrics.maximumPumpDurationNs)
        assertEquals(1, metrics.slowPumpCount)
    }
}
