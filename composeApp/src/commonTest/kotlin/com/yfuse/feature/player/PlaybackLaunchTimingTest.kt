package com.yfuse.feature.player

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLaunchTimingTest {
    @Test
    fun video_launch_keeps_background_work_paused_after_first_audio() {
        assertFalse(releasesPlaybackBackgroundWork("first_audio_output", "Movie"))
        assertFalse(releasesPlaybackBackgroundWork("first_audio_output", null))
        assertTrue(releasesPlaybackBackgroundWork("first_audio_output", "Audio"))
        assertTrue(releasesPlaybackBackgroundWork("first_video_output", "Movie"))
        assertTrue(releasesPlaybackBackgroundWork("startup_error", "Movie"))
    }

    @Test
    fun optional_requests_wait_for_output_or_failure() =
        runTest {
            for (stage in listOf("first_video_output", "first_audio_output", "startup_error")) {
                val timing = PlaybackLaunchTiming()
                timing.claim()
                val waiting = async { timing.awaitForeground() }
                runCurrent()
                assertFalse(waiting.isCompleted)
                timing.stage(stage, output = true)
                runCurrent()
                assertTrue(waiting.isCompleted)
            }
        }

    @Test
    fun missing_player_does_not_block_catalog_forever() =
        runTest {
            PlaybackLaunchTiming().also { it.claim() }.awaitForeground()
            assertTrue(testScheduler.currentTime == 30_000L)
        }

    @Test
    fun preparation_does_not_wait_for_a_player_and_claim_is_single_use() =
        runTest {
            val timing = PlaybackLaunchTiming()
            timing.awaitForeground()
            assertTrue(testScheduler.currentTime == 0L)
            assertTrue(timing.claim())
            assertFalse(timing.claim())
            timing.bindSession("new-session")
            assertTrue(timing.matchesSession("new-session"))
            assertFalse(timing.matchesSession("previous-session"))
        }

    /**
     * Calendar fan-out and playback-sync applies poll this instead of awaiting one launch, since
     * an unclaimed timing (preparation with no tap yet) must not make them defer.
     */
    @Test
    fun holds_background_priority_only_once_claimed() {
        val timing = PlaybackLaunchTiming()
        assertFalse(timing.holdsBackgroundPriority())

        timing.claim()
        assertTrue(timing.holdsBackgroundPriority())
    }

    @Test
    fun holds_background_priority_releases_at_first_output() {
        val timing = PlaybackLaunchTiming().also { it.claim() }
        assertTrue(timing.holdsBackgroundPriority())

        timing.stage("first_video_output", output = true)

        assertFalse(timing.holdsBackgroundPriority())
    }

    @Test
    fun holds_background_priority_releases_on_startup_error_too() {
        val timing = PlaybackLaunchTiming().also { it.claim() }

        timing.stage("startup_error", output = true)

        assertFalse(timing.holdsBackgroundPriority())
    }

    @Test
    fun any_holds_background_priority_is_true_while_a_registered_launch_holds_it() {
        val serverId = "launch-timing-test-server"
        val itemId = "launch-timing-test-item-${Random.nextLong()}"
        val timing = PlaybackLaunchTiming().also { it.claim() }
        PlaybackLaunchTimings.register(serverId, itemId, timing)
        try {
            assertTrue(PlaybackLaunchTimings.anyHoldsBackgroundPriority())
        } finally {
            PlaybackLaunchTimings.remove(serverId, itemId)
        }
    }
}
