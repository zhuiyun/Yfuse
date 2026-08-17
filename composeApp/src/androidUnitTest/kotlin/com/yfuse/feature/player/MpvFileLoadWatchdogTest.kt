package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MpvFileLoadWatchdogTest {
    @Test
    fun optical_and_large_prores_sources_get_long_startup_windows() {
        val iso =
            mpvFileLoadWatchdogPolicy(
                url = "yfusebd://7",
                container = "ISO",
                discSource = true,
                sourceVideoCodec = "hevc",
            )
        val bdmv =
            mpvFileLoadWatchdogPolicy(
                url = "yfusebdmv://8",
                container = "BDMV",
                discSource = true,
                sourceVideoCodec = "hevc",
            )
        val proRes =
            mpvFileLoadWatchdogPolicy(
                url = "file:///Movies/feature.mov",
                container = "MOV",
                discSource = false,
                sourceVideoCodec = "prores_ks",
            )
        val ordinary =
            mpvFileLoadWatchdogPolicy(
                url = "file:///Movies/feature.mp4",
                container = "MP4",
                discSource = false,
                sourceVideoCodec = "h264",
            )

        assertTrue(iso.graceMs >= 60_000L)
        assertEquals(iso, bdmv)
        assertTrue(proRes.graceMs >= 45_000L)
        assertTrue(ordinary.graceMs < proRes.graceMs)
    }

    @Test
    fun old_eight_second_deadline_can_no_longer_fail_an_optical_load() {
        val policy =
            mpvFileLoadWatchdogPolicy(
                url = "yfusebd://3",
                container = "ISO",
                discSource = true,
                sourceVideoCodec = "hevc",
            )

        assertEquals(
            MpvFileLoadWatchdogDecision.Wait,
            evaluateMpvFileLoadWatchdog(
                attempt = 3L,
                activeAttempt = 3L,
                released = false,
                buffering = true,
                startedAtMs = 1_000L,
                lastProgressMs = 1_000L,
                nowMs = 9_001L,
                policy = policy,
            ),
        )
    }

    @Test
    fun progress_heartbeat_keeps_a_slow_source_alive_after_grace() {
        val policy = MpvFileLoadWatchdogPolicy(graceMs = 10_000L, stallMs = 5_000L, hardLimitMs = 30_000L)

        assertEquals(
            MpvFileLoadWatchdogDecision.Wait,
            evaluateMpvFileLoadWatchdog(
                attempt = 1L,
                activeAttempt = 1L,
                released = false,
                buffering = true,
                startedAtMs = 0L,
                lastProgressMs = 11_000L,
                nowMs = 14_000L,
                policy = policy,
            ),
        )
        assertEquals(
            MpvFileLoadWatchdogDecision.StallTimeout,
            evaluateMpvFileLoadWatchdog(
                attempt = 1L,
                activeAttempt = 1L,
                released = false,
                buffering = true,
                startedAtMs = 0L,
                lastProgressMs = 11_000L,
                nowMs = 16_001L,
                policy = policy,
            ),
        )
    }

    @Test
    fun hard_limit_wins_even_when_progress_is_still_arriving() {
        val policy = MpvFileLoadWatchdogPolicy(graceMs = 10_000L, stallMs = 5_000L, hardLimitMs = 30_000L)

        assertEquals(
            MpvFileLoadWatchdogDecision.HardTimeout,
            evaluateMpvFileLoadWatchdog(
                attempt = 2L,
                activeAttempt = 2L,
                released = false,
                buffering = true,
                startedAtMs = 0L,
                lastProgressMs = 29_999L,
                nowMs = 30_000L,
                policy = policy,
            ),
        )
    }

    @Test
    fun stale_loaded_or_released_attempts_are_ignored() {
        val policy = MpvFileLoadWatchdogPolicy(graceMs = 1_000L, stallMs = 1_000L, hardLimitMs = 5_000L)
        val common =
            arrayOf(
                evaluateMpvFileLoadWatchdog(1L, 2L, false, true, 0L, 0L, 4_000L, policy),
                evaluateMpvFileLoadWatchdog(2L, 2L, true, true, 0L, 0L, 4_000L, policy),
                evaluateMpvFileLoadWatchdog(2L, 2L, false, false, 0L, 0L, 4_000L, policy),
            )

        common.forEach { assertEquals(MpvFileLoadWatchdogDecision.Ignore, it) }
    }
}
