package com.yfuse.core2.learning

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class YPlaybackLearningTest {
    private var now = 1_000L
    private val store = InMemoryYPlaybackLearningStore()
    private val engine = YPlaybackLearningEngine(store, nowEpochMs = { now })

    @Test
    fun `learns quality metrics without media identity`() {
        val record =
            engine.record(
                key(),
                YPlaybackObservation(
                    rendered = true,
                    playedDurationMs = 60_000L,
                    droppedFrames = 3,
                    maximumAbsoluteAvDriftMs = 40L,
                    maximumThermalStatus = 2,
                    batteryDeltaPermille = -8,
                ),
            )

        assertEquals(1, record.attempts)
        assertEquals(1, record.successfulAttempts)
        assertEquals(3L, record.droppedFrames)
        assertEquals(8L, record.batteryDeltaPermille)
        assertEquals(YLearnedRouteAdvice.Allow, engine.advice(key()))
    }

    @Test
    fun `three consecutive unrendered attempts avoid exact route`() {
        repeat(3) {
            now++
            engine.record(key(), YPlaybackObservation(rendered = false, playedDurationMs = 0L))
        }

        assertEquals(YLearnedRouteAdvice.Avoid, engine.advice(key()))
        assertEquals(YLearnedRouteAdvice.Allow, engine.advice(key().copy(route = YPlaybackRoute.NativeDirect)))
    }

    @Test
    fun `underruns drift resets and heavy frame drops penalize a rendered route`() {
        engine.record(
            key(),
            YPlaybackObservation(
                rendered = true,
                playedDurationMs = 5_000L,
                droppedFrames = 10,
                codecResets = 1,
                audioUnderruns = 3,
                maximumAbsoluteAvDriftMs = 300L,
            ),
        )

        assertEquals(YLearnedRouteAdvice.Penalize, engine.advice(key()))
    }

    @Test
    fun `repeated severe measured degradation avoids only the exact route`() {
        repeat(3) {
            now++
            engine.record(
                key(),
                YPlaybackObservation(
                    rendered = true,
                    playedDurationMs = 60_000L,
                    droppedFrames = 240,
                    codecResets = 1,
                    audioUnderruns = 4,
                    maximumAbsoluteAvDriftMs = 1_200L,
                ),
            )
        }

        assertEquals(YLearnedRouteAdvice.Avoid, engine.advice(key()))
        assertEquals(
            YLearnedRouteAdvice.Allow,
            engine.advice(key().copy(decoderName = "c2.other.hevc")),
        )
    }

    private fun key() =
        YPlaybackLearningKey(
            route = YPlaybackRoute.NativeTunnel,
            container = YContainer.Matroska,
            videoCodec = YVideoCodec.H265,
            hdrType = YHdrType.Hdr10,
            decoderName = "c2.vendor.hevc",
        )
}
