package com.yfuse.feature.player

import com.yfuse.core.data.DanmakuComment
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuHeatTest {
    private fun heatAt(vararg timesMs: Long) = DanmakuHeat(timesMs, IntArray(timesMs.size) { 1 })

    @Test
    fun commentsAreCountedIntoHundredthsOfTheFile() {
        val counts = danmakuHeatBuckets(heatAt(0L, 500L, 999L, 1_000L, 99_999L), durationMs = 100_000L)

        assertEquals(100, counts.size)
        assertEquals(3f, counts[0])
        assertEquals(1f, counts[1])
        assertEquals(1f, counts[99])
        assertEquals(5f, counts.sum())
    }

    @Test
    fun commentsOutsideTheFileCountNowhere() {
        val counts = danmakuHeatBuckets(heatAt(-1L, 100_000L, 250_000L, 50_000L), durationMs = 100_000L)
        assertEquals(1f, counts.sum())
        assertEquals(0f, danmakuHeatBuckets(heatAt(10L), durationMs = 0L).sum())
    }

    @Test
    fun aMergedLineCountsForEveryLineItStandsFor() {
        val heat =
            danmakuHeatOf(
                listOf(
                    DanmakuComment(timeMs = 10_000L, text = "笑死", repeats = 128),
                    DanmakuComment(timeMs = 10_500L, text = "前方高能"),
                ),
            )!!
        assertContentEquals(intArrayOf(128, 1), heat.weights)
        assertEquals(129f, danmakuHeatBuckets(heat, durationMs = 100_000L)[10])
        assertNull(danmakuHeatOf(emptyList()))
    }

    @Test
    fun smoothingSpreadsAPeakBinomiallyAndScalesItToOne() {
        val counts = FloatArray(100).also { it[50] = 16f }
        val smoothed = smoothDanmakuHeat(counts)

        assertEquals(1f, smoothed[50])
        assertEquals(4f / 6f, smoothed[49], 0.0001f)
        assertEquals(4f / 6f, smoothed[51], 0.0001f)
        assertEquals(1f / 6f, smoothed[48], 0.0001f)
        assertEquals(1f / 6f, smoothed[52], 0.0001f)
        assertEquals(0f, smoothed[47])
    }

    @Test
    fun theEndsAreNotQuietenedByTheKernelRunningOffThem() {
        val flat = FloatArray(100) { 3f }
        smoothDanmakuHeat(flat).forEach { assertEquals(1f, it, 0.0001f) }
        val atStart = smoothDanmakuHeat(FloatArray(100).also { it[0] = 8f })
        assertEquals(1f, atStart[0])
        assertTrue(atStart[1] < atStart[0] && atStart[2] < atStart[1] && atStart[3] == 0f)
    }

    @Test
    fun thereIsNoCurveWithoutCommentsInsideTheFile() {
        assertNull(danmakuHeatCurve(heatAt(200_000L), durationMs = 100_000L))
        val curve = danmakuHeatCurve(heatAt(40_000L, 40_100L, 90_000L), durationMs = 100_000L)!!
        assertEquals(100, curve.size)
        assertEquals(1f, curve[40])
        assertTrue(curve[90] in 0.4f..0.6f)
    }
}
