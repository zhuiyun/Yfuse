package com.yfuse.core.cast

import kotlin.test.Test
import kotlin.test.assertEquals

class DlnaStartupTest {
    private val playing = CastPlaybackStatus.Playing

    @Test
    fun playing_at_a_frozen_zero_is_not_a_start_and_fails_when_the_budget_runs_out() {
        // The 2026-10-01 report: PLAYING from the first poll, 0:00:00 on every one after it.
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        for (second in 1L..74L) {
            assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, second * 1_000L))
        }
        assertEquals(
            DlnaStartVerdict.Failed(DlnaStartFailure.NoProgress),
            monitor.observe(playing, 0L, 75_000L),
        )
    }

    @Test
    fun a_moving_clock_is_a_start_even_when_the_first_answers_were_transitioning() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(CastPlaybackStatus.Buffering, null, 1_000L))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 68_000L, 2_000L))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 69_000L, 3_000L))
        assertEquals(DlnaStartVerdict.Started(69_600L), monitor.observe(playing, 69_600L, 4_000L))
    }

    @Test
    fun a_seek_rebases_progress_so_the_jump_itself_is_not_playback() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 1_000L))
        monitor.rebase()
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 68_187L, 2_000L))
        assertEquals(DlnaStartVerdict.Started(70_000L), monitor.observe(playing, 70_000L, 4_000L))
    }

    @Test
    fun stopped_twice_in_a_row_fails_but_once_on_the_way_to_transitioning_does_not() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(CastPlaybackStatus.Ended, null, 1_000L))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(CastPlaybackStatus.Buffering, null, 2_000L))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(CastPlaybackStatus.Ended, null, 3_000L))
        assertEquals(
            DlnaStartVerdict.Failed(DlnaStartFailure.Stopped),
            monitor.observe(CastPlaybackStatus.Ended, 0L, 4_000L),
        )
    }

    @Test
    fun a_renderer_without_a_clock_is_taken_at_its_word_after_two_seconds_of_playing() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, null, 1_000L))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, null, 2_000L))
        assertEquals(DlnaStartVerdict.Started(null), monitor.observe(playing, null, 3_000L))
    }

    @Test
    fun relayed_media_the_renderer_never_requested_fails_early() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        val untouched = DlnaRelayActivity(requests = 0, bytesServed = 0L, lastActivityAtMs = null)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 19_000L, untouched))
        assertEquals(
            DlnaStartVerdict.Failed(DlnaStartFailure.NeverRequested),
            monitor.observe(playing, 0L, 20_000L, untouched),
        )
    }

    @Test
    fun relayed_media_read_and_then_left_alone_was_abandoned() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        val probed = DlnaRelayActivity(requests = 3, bytesServed = 2_000_000L, lastActivityAtMs = 4_000L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 28_000L, probed))
        assertEquals(
            DlnaStartVerdict.Failed(DlnaStartFailure.Abandoned),
            monitor.observe(playing, 0L, 29_000L, probed),
        )
    }

    @Test
    fun steady_relay_reading_while_playing_shows_a_frozen_clock_renderer_is_playing() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        var verdict: DlnaStartVerdict = DlnaStartVerdict.Waiting
        for (second in 1L..11L) {
            val activity =
                DlnaRelayActivity(
                    requests = 2,
                    bytesServed = second * 2_000_000L,
                    lastActivityAtMs = second * 1_000L,
                )
            verdict = monitor.observe(playing, 0L, second * 1_000L, activity)
            if (verdict != DlnaStartVerdict.Waiting) break
        }
        assertEquals(DlnaStartVerdict.Started(null), verdict)
    }

    @Test
    fun a_clock_left_untrusted_at_start_is_trusted_once_it_moves() {
        val watch = DlnaClockWatch()
        assertEquals(false, watch.moved(playing, 0L))
        assertEquals(false, watch.moved(playing, 0L))
        assertEquals(false, watch.moved(CastPlaybackStatus.Paused, 9_000L))
        assertEquals(false, watch.moved(playing, null))
        assertEquals(true, watch.moved(playing, 1_600L))
    }

    @Test
    fun time_spent_paused_does_not_use_up_the_start_budget() {
        val monitor = DlnaStartMonitor(startedAtMs = 0L)
        val probed = DlnaRelayActivity(requests = 1, bytesServed = 500_000L, lastActivityAtMs = 1_000L)
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 1_000L, probed))
        for (second in 2L..120L) {
            assertEquals(
                DlnaStartVerdict.Waiting,
                monitor.observe(CastPlaybackStatus.Paused, 0L, second * 1_000L, probed),
            )
        }
        // Resumed at 121 s: the quiet spell and the budget both start again from here.
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 121_000L, probed))
        assertEquals(DlnaStartVerdict.Waiting, monitor.observe(playing, 0L, 145_000L, probed))
        assertEquals(
            DlnaStartVerdict.Failed(DlnaStartFailure.Abandoned),
            monitor.observe(playing, 0L, 146_000L, probed),
        )
    }
}
