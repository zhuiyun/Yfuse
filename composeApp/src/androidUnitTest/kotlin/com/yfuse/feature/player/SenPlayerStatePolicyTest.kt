package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferRangeSource
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SenPlayerStatePolicyTest {
    @Test
    fun embedded_mediacodec_draws_confirm_output_without_optional_decode_frame_counters() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(sourceId = 7, draws = 100)
        assertFalse(gate.observe(7, 100, 320, 180, true))
        assertTrue(gate.observe(7, 101, 320, 180, true))
        assertTrue(gate.observe(7, 101, 320, 180, true)) // A paused displayed frame stays verified.
    }

    @Test
    fun previous_source_draws_and_absent_surfaces_cannot_confirm_the_new_output() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(8, 100)
        assertFalse(gate.observe(7, 200, 320, 180, true))
        assertFalse(gate.observe(0, 200, 320, 180, true))
        assertFalse(gate.observe(8, 101, 320, 180, false))
        assertFalse(gate.observe(8, 101, 0, 0, true))
        gate.reset(8, 101) // Replacing the Surface or seeking invalidates the previous picture.
        assertFalse(gate.observe(8, 101, 320, 180, true))
        assertTrue(gate.observe(8, 102, 320, 180, true))
    }

    @Test
    fun native_counter_reset_does_not_wait_for_the_previous_film_frame_count() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(9, 90_000)
        assertTrue(gate.observe(9, 1, 320, 180, true))
        assertTrue(gate.observe(9, 2, 320, 180, true))
        assertFalse(gate.observe(9, 0, 320, 180, true))
        assertTrue(gate.observe(9, 1, 320, 180, true))
    }

    @Test
    fun a_single_paused_frame_remains_verified_after_an_observed_counter_reset() {
        val gate = SenPlayerVideoReadinessGate()
        gate.reset(10, 90_000)
        assertTrue(gate.observe(10, 1, 320, 180, true))
        repeat(3) { assertTrue(gate.observe(10, 1, 320, 180, true)) }
        assertFalse(gate.observe(9, 1, 320, 180, true))
        assertFalse(gate.observe(10, 1, 320, 180, false))
        assertFalse(gate.observe(10, 1, 0, 0, true))
    }

    @Test
    fun pending_selection_and_retry_clear_old_source_evidence_but_keep_position_and_speed() {
        val old = populatedState()
        val next = old.copy(currentIndex = 2, positionMs = 45_000).forSenPlayerLoad("new-session", 12)
        assertEquals(2, next.currentIndex)
        assertEquals(45_000L, next.positionMs)
        assertEquals(1.25f, next.speed)
        assertEquals(0L, next.durationMs)
        assertEquals(0, next.videoHeight)
        assertEquals(0, next.diagnostics.videoWidth)
        assertFalse(next.playing)
        assertTrue(next.buffering)
        assertFalse(next.ended)
        assertNull(next.error)
        assertNull(next.errorKind)
        assertFalse(next.fallbacksExhausted)
        assertFalse(next.automaticFallbackBlocked)
        assertTrue(next.audioTracks.isEmpty())
        assertTrue(next.subtitleTracks.isEmpty())
        assertNull(next.secondarySubtitleTrackId)
        assertEquals(YBufferSnapshot(sessionId = "new-session", generation = 12), next.bufferSnapshot)
        assertEquals(PlaybackOutputReadiness.Waiting, next.diagnostics.videoReadiness)
        assertEquals(PlaybackOutputReadiness.Unknown, next.diagnostics.audioReadiness)
    }

    @Test
    fun failure_stops_loading_and_playing_and_removes_old_cache_without_losing_retry_position() {
        val failed = populatedState().forSenPlayerFailure("license denied", PlaybackFailureKind.Authorization)
        assertFalse(failed.playing)
        assertFalse(failed.buffering)
        assertFalse(failed.ended)
        assertEquals(10_000L, failed.positionMs)
        assertEquals(PlaybackFailureKind.Authorization, failed.errorKind)
        assertEquals(0L, failed.bufferedPositionMs)
        assertTrue(failed.bufferSnapshot.ranges.isEmpty())
        assertEquals(YBufferRangeCoverage.Unknown, failed.bufferSnapshot.coverage)
        assertEquals(0L, failed.diagnostics.bufferedDurationMs)
        assertEquals(PlaybackOutputReadiness.Unknown, failed.diagnostics.videoReadiness)
        assertTrue(failed.automaticFallbackBlocked)
    }

    @Test
    fun cache_probe_failure_discards_stale_islands_and_reports_unknown() {
        val cache = senPlayerCacheState("s", 4, false, listOf(longArrayOf(0, 90_000)), 5_000, 100_000, 90_000, 50_000)
        assertEquals(YBufferRangeCoverage.Unknown, cache.snapshot.coverage)
        assertTrue(cache.snapshot.ranges.isEmpty())
        assertEquals(5_000L, cache.endMs)
        assertEquals(0L, cache.durationMs)
    }

    @Test
    fun sparse_cache_islands_do_not_become_continuous_buffer_duration() {
        val cache =
            senPlayerCacheState(
                "s",
                5,
                true,
                listOf(longArrayOf(0, 8_000), longArrayOf(80_000, 90_000)),
                5_000,
                100_000,
                90_000,
                3_000,
            )
        assertEquals(listOf(YBufferedRange(0, 8_000), YBufferedRange(80_000, 90_000)), cache.snapshot.ranges)
        assertEquals(YBufferRangeCoverage.SeekableRanges, cache.snapshot.coverage)
        assertEquals(YBufferRangeSource.SenPlayerNative, cache.snapshot.source)
        assertEquals(3_000L, cache.durationMs)
        assertEquals(90_000L, cache.endMs)
    }

    @Test
    fun seek_can_be_saved_before_duration_exists_and_is_clamped_when_known() {
        assertEquals(45_000L, senPlayerSeekPosition(45_000, 0))
        assertEquals(0L, senPlayerSeekPosition(-5, 0))
        assertEquals(30_000L, senPlayerSeekPosition(45_000, 30_000))
    }

    @Test
    fun pending_seek_waits_for_an_active_source_and_known_duration_before_reaching_jni() {
        assertNull(senPlayerPendingSeekTarget(45_000, 0, "playing"))
        assertNull(senPlayerPendingSeekTarget(45_000, 60_000, "loading"))
        assertNull(senPlayerPendingSeekTarget(45_000, 60_000, "failed"))
        assertNull(senPlayerPendingSeekTarget(null, 60_000, "playing"))
        assertEquals(45_000L, senPlayerPendingSeekTarget(45_000, 60_000, "paused"))
        assertEquals(60_000L, senPlayerPendingSeekTarget(Long.MAX_VALUE, 60_000, "playing"))
    }

    @Test
    fun pending_seek_does_not_pin_the_clock_of_an_active_unknown_duration_source() {
        val pending = 45_000L
        for (phase in listOf("playing", "paused", "buffering", "seeking")) {
            assertNull(senPlayerPendingSeekTarget(pending, 0, phase))
            assertEquals(5_000L, senPlayerReportedPosition(pending, 5_000, phase))
            assertEquals(6_000L, senPlayerReportedPosition(pending, 6_000, phase))
        }
        // The original request is still eligible once the real duration becomes known.
        assertEquals(45_000L, senPlayerPendingSeekTarget(pending, 60_000, "playing"))
    }

    @Test
    fun only_loading_reports_the_pending_target_instead_of_the_native_clock() {
        assertEquals(45_000L, senPlayerReportedPosition(45_000, 5_000, "loading"))
        assertEquals(5_000L, senPlayerReportedPosition(null, 5_000, "loading"))
        assertEquals(0L, senPlayerReportedPosition(-1, 5_000, "loading"))
        assertEquals(60_000L, senPlayerReportedPosition(45_000, 60_000, "ended"))
        assertEquals(0L, senPlayerReportedPosition(45_000, -1, "playing"))
    }

    @Test
    fun natural_end_advances_once_and_can_be_rearmed_after_seek_or_new_source() {
        val gate = SenPlayerEndGate()
        assertFalse(gate.advance(false, true, true, false, true))
        assertTrue(gate.advance(true, true, true, false, true))
        assertFalse(gate.advance(true, true, true, false, true))
        gate.reset()
        assertTrue(gate.advance(true, true, true, false, true))
    }

    @Test
    fun paused_or_pause_at_end_or_final_item_never_auto_starts_another_source() {
        assertFalse(SenPlayerEndGate().advance(true, false, true, false, true))
        assertFalse(SenPlayerEndGate().advance(true, true, true, true, true))
        assertFalse(SenPlayerEndGate().advance(true, true, false, false, true))
        assertFalse(SenPlayerEndGate().advance(true, true, true, false, false))
    }

    private fun populatedState() =
        PlaybackState(
            playing = true,
            buffering = true,
            positionMs = 10_000,
            durationMs = 100_000,
            speed = 1.25f,
            videoHeight = 1080,
            audioTracks = listOf(EngineTrack("1", "Audio", null, true)),
            subtitleTracks = listOf(EngineTrack("2", "Subtitle", null, true)),
            secondarySubtitleTrackId = "3",
            ended = true,
            error = "old failure",
            errorKind = PlaybackFailureKind.Unknown,
            fallbacksExhausted = true,
            automaticFallbackBlocked = true,
            bufferedPositionMs = 90_000,
            bufferSnapshot =
                YBufferSnapshot(
                    sessionId = "old",
                    generation = 3,
                    source = YBufferRangeSource.SenPlayerNative,
                    coverage = YBufferRangeCoverage.SeekableRanges,
                    ranges = listOf(YBufferedRange(0, 90_000)),
                ),
            diagnostics =
                PlaybackDiagnostics(
                    engine = "SenPlayer",
                    videoWidth = 1920,
                    videoReadiness = PlaybackOutputReadiness.Rendering,
                    audioReadiness = PlaybackOutputReadiness.Rendering,
                    bufferedDurationMs = 80_000,
                ),
        )
}
