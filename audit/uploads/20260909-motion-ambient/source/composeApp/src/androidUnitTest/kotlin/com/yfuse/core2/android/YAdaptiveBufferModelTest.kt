package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals

class YAdaptiveBufferModelTest {
    @Test
    fun `pause preserves player reported buffer`() {
        val model = YAdaptiveBufferModel()
        model.applyFeedback(
            YAdaptivePlaybackFeedback(
                bufferedDurationUs = 8_000_000L,
                playing = false,
                speed = 1f,
                generation = 1L,
            ),
            recordedAtNs = 1_000_000_000L,
        )

        assertEquals(8_000_000L, model.estimate(nowNs = 11_000_000_000L))
    }

    @Test
    fun `playing buffer drains at actual playback speed`() {
        val model = YAdaptiveBufferModel()
        model.applyFeedback(
            YAdaptivePlaybackFeedback(
                bufferedDurationUs = 8_000_000L,
                playing = true,
                speed = 2f,
                generation = 1L,
            ),
            recordedAtNs = 1_000_000_000L,
        )

        assertEquals(6_000_000L, model.estimate(nowNs = 2_000_000_000L))
    }

    @Test
    fun `new seek generation replaces buffer and rejects stale feedback`() {
        val model = YAdaptiveBufferModel()
        model.applyFeedback(
            YAdaptivePlaybackFeedback(12_000_000L, playing = false, speed = 1f, generation = 2L),
            recordedAtNs = 2_000_000_000L,
        )
        model.applyFeedback(
            YAdaptivePlaybackFeedback(1_000_000L, playing = false, speed = 1f, generation = 3L),
            recordedAtNs = 3_000_000_000L,
        )
        model.applyFeedback(
            YAdaptivePlaybackFeedback(20_000_000L, playing = false, speed = 1f, generation = 2L),
            recordedAtNs = 4_000_000_000L,
        )

        assertEquals(1_000_000L, model.estimate(nowNs = 4_000_000_000L))
    }

    @Test
    fun `fresh player feedback prevents completed segment double count`() {
        val model = YAdaptiveBufferModel()
        model.applyFeedback(
            YAdaptivePlaybackFeedback(5_000_000L, playing = false, speed = 1f, generation = 1L),
            recordedAtNs = 1_000_000_000L,
        )

        model.completeSegment(
            durationUs = 4_000_000L,
            contiguous = true,
            nowNs = 2_000_000_000L,
        )

        assertEquals(5_000_000L, model.estimate(nowNs = 2_000_000_000L))
    }
}
