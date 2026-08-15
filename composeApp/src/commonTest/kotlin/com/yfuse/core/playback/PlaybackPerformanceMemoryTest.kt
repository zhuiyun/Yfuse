package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackPerformanceMemoryTest {
    @Test
    fun benchmarks_can_be_cleared_and_persisted() {
        var persisted = emptyList<PlaybackPerformanceRecord>()
        val memory =
            PlaybackPerformanceMemory(
                nowEpochMs = { 1_000L },
                onChanged = { persisted = it },
            )
        memory.record("MKV|H264", PlayerEngine.Exo, healthyAssessment())

        memory.clear()

        assertTrue(memory.snapshot().isEmpty())
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun two_degraded_sessions_produce_an_engine_cost_without_media_identity() {
        var now = 1_000L
        val memory = PlaybackPerformanceMemory(nowEpochMs = { now })
        repeat(2) {
            memory.record(SIGNATURE, PlayerEngine.Exo, degradedAssessment())
            now += 1_000L
        }

        assertTrue(memory.engineCosts(SIGNATURE).getValue(PlayerEngine.Exo) > 0)
        assertEquals(2, memory.snapshot().single().sessions)
        assertEquals(SIGNATURE, memory.snapshot().single().signature)
    }

    @Test
    fun stale_performance_records_expire() {
        var now = 10_000L
        val initial =
            listOf(
                PlaybackPerformanceRecord(
                    signature = SIGNATURE,
                    engine = PlayerEngine.Exo,
                    sessions = 3,
                    averageStartupMs = 8_000L,
                    averageRebufferEventsPerMinute = 0f,
                    averageDroppedFramesPerMinute = 0f,
                    lastObservedEpochMs = now,
                ),
            )
        val memory =
            PlaybackPerformanceMemory(
                ttlMs = 1_000L,
                nowEpochMs = { now },
                initialRecords = initial,
            )

        now += 1_001L

        assertTrue(memory.engineCosts(SIGNATURE).isEmpty())
        assertTrue(memory.snapshot().isEmpty())
    }

    private fun degradedAssessment() =
        PlaybackHealthAssessment(
            grade = PlaybackHealthGrade.Degraded,
            startupTimeMs = 8_000L,
            observedPlaybackMs = 60_000L,
            rebufferEvents = 0,
            droppedFrames = 12,
            droppedFramesPerMinute = 12f,
            evaluationReady = true,
            enginePenaltyRecommended = false,
        )

    private fun healthyAssessment() =
        PlaybackHealthAssessment(
            grade = PlaybackHealthGrade.Healthy,
            startupTimeMs = 1_000L,
            observedPlaybackMs = 60_000L,
            rebufferEvents = 0,
            droppedFrames = 0,
            droppedFramesPerMinute = 0f,
            evaluationReady = true,
            enginePenaltyRecommended = false,
        )

    private companion object {
        const val SIGNATURE = "MKV|HEVC|2160|60|10|HDR10"
    }
}
