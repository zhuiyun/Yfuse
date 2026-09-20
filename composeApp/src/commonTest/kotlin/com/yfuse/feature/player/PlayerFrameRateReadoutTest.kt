package com.yfuse.feature.player

import com.yfuse.core2.api.YFrameRateSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerFrameRateReadoutTest {
    private val playing = PlaybackState(playing = true, buffering = false)

    @Test
    fun source_fps_is_never_substituted_for_measured_output() {
        val state = playing.copy(diagnostics = PlaybackDiagnostics(frameRate = 23.976f))
        assertEquals("片源 23.98 FPS", state.sourceFrameRateLabel())
        assertEquals("实时输出 — FPS · 暂无数据", state.outputFrameRateLabel(1_000L))
    }

    @Test
    fun measured_zero_is_valid_but_stale_future_and_invalid_samples_are_not() {
        fun sample(
            rate: Float,
            at: Long,
        ) = playing.copy(diagnostics = PlaybackDiagnostics(renderedFrameRate = YFrameRateSample(rate, at)))
        assertEquals("实时输出 0.00 FPS", sample(0f, 1_000L).outputFrameRateLabel(2_000L))
        assertEquals("实时输出 47.95 FPS", sample(47.952f, 1_000L).outputFrameRateLabel(2_000L))
        assertTrue(sample(60f, 1_000L).outputFrameRateLabel(4_001L).contains("暂无数据"))
        assertTrue(sample(60f, 3_000L).outputFrameRateLabel(2_000L).contains("暂无数据"))
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { invalid ->
            assertTrue(sample(invalid, 1_000L).outputFrameRateLabel(2_000L).contains("暂无数据"))
            assertEquals(
                "片源 — FPS",
                playing.copy(diagnostics = PlaybackDiagnostics(frameRate = invalid)).sourceFrameRateLabel(),
            )
        }
    }

    @Test
    fun paused_buffering_and_ended_states_do_not_reuse_a_playing_sample() {
        val measured =
            playing.copy(
                diagnostics = PlaybackDiagnostics(renderedFrameRate = YFrameRateSample(60f, 1_000L)),
            )
        assertTrue(measured.copy(playing = false).outputFrameRateLabel(1_100L).endsWith("已暂停"))
        assertTrue(measured.copy(buffering = true).outputFrameRateLabel(1_100L).endsWith("缓冲中"))
        assertTrue(measured.copy(ended = true).outputFrameRateLabel(1_100L).endsWith("已结束"))
    }
}
