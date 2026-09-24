package com.yfuse.core2.android

import com.yfuse.core.playback.PlaybackDrmConfiguration
import com.yfuse.core.playback.PlaybackDrmScheme
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.network.YBufferPlan
import com.yfuse.core2.network.YPlaybackBufferGate
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackStartupLatencyTest {
    @Test
    fun deadline_is_not_a_format_failure_and_does_not_trigger_a_second_full_probe() {
        val item = YMediaItem("movie", "https://host/movie.mp4")
        val deadline = YCore2ProbeResult.Failure(YCore2ProbeFailure.Deadline)
        assertTrue(skipEnhancedProbeAfterDeadline(deadline, item))
        // A lane still busy after playback's wait carries no evidence either.
        assertTrue(skipEnhancedProbeAfterDeadline(YCore2ProbeResult.Failure(YCore2ProbeFailure.Busy), item))
        assertFalse(
            skipEnhancedProbeAfterDeadline(YCore2ProbeResult.Failure(YCore2ProbeFailure.UnknownVideoCodec), item),
        )
        assertFalse(
            skipEnhancedProbeAfterDeadline(deadline, item.copy(sourceHints = YMediaSourceHints(dolbyVision = true))),
        )
        assertFalse(
            skipEnhancedProbeAfterDeadline(
                deadline,
                item.copy(
                    drmConfiguration =
                        PlaybackDrmConfiguration(
                            scheme = PlaybackDrmScheme.Widevine,
                            licenseUri = "https://host/license",
                        ),
                ),
            ),
        )
    }

    @Test
    fun network_timeout_is_not_retried_by_changing_demuxers() {
        val failure = IllegalStateException("extractor", SocketTimeoutException("private host"))
        val typed = failure.mediaSourceFailure()
        assertEquals(YPlaybackFailureCategory.Network, typed?.category)
        assertFailsWith<YPlaybackException> {
            YCore2ProbeResult.Failure(YCore2ProbeFailure.SourceUnavailable, typed).sourceSuccessOrThrow()
        }
    }

    @Test
    fun active_range_survives_short_window_changes_but_not_a_stall() {
        assertTrue(keepActiveTransportPrefetch(0))
        assertTrue(keepActiveTransportPrefetch(1_500))
        assertFalse(keepActiveTransportPrefetch(4_000))
        assertFalse(keepActiveTransportPrefetch(-1))
    }

    @Test
    fun buffer_wait_does_not_include_pause_or_previous_seek() {
        val clock = AndroidBufferWaitClock()
        assertEquals(0L, clock.observe(0L, true, 1))
        assertEquals(10_000_000L, clock.observe(10_000_000_000L, true, 1))
        clock.reset()
        assertEquals(0L, clock.observe(100_000_000_000L, true, 1))
        assertEquals(0L, clock.observe(110_000_000_000L, true, 2))
        assertEquals(0L, clock.observe(120_000_000_000L, false, 2))
    }

    @Test
    fun long_rebuffer_relaxes_only_extra_reserve_and_never_releases_an_empty_queue() {
        val gate = YPlaybackBufferGate(true, 2_500_000L)
        gate.updateThresholds(YBufferPlan(20_000_000L, 2_500_000L, 1024))
        assertTrue(gate.evaluate(500_000L, false).outputAllowed)
        gate.markStarved()
        assertTrue(gate.evaluate(2_500_000L, false).outputAllowed)
        gate.markStarved()
        assertFalse(gate.evaluate(2_500_000L, false).outputAllowed)
        assertFalse(gate.evaluate(0L, false, rebufferWaitUs = 10_000_000L).outputAllowed)
        assertTrue(gate.evaluate(2_500_000L, false, rebufferWaitUs = 10_000_000L).outputAllowed)
    }
}
