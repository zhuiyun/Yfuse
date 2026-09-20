package com.yfuse.feature.player

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackLaunchTimingTest {
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
}
