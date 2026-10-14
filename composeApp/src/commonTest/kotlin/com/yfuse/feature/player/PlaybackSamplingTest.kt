package com.yfuse.feature.player

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackSamplingTest {
    @Test
    fun native_event_bursts_are_conflated_and_idle_sampling_backs_off() =
        runTest {
            val signals = Channel<Unit>(Channel.CONFLATED)
            var samples = 0
            val job =
                backgroundScope.launch {
                    samplePlaybackUntilCancelled(signals, { 1_000L }) { samples++ }
                }
            runCurrent()
            assertEquals(1, samples)
            repeat(100) { signals.trySend(Unit) }
            advanceTimeBy(99L)
            assertEquals(1, samples)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(2, samples)
            advanceTimeBy(999L)
            assertEquals(2, samples)
            advanceTimeBy(1L)
            runCurrent()
            assertEquals(3, samples)
            signals.close()
            advanceTimeBy(100L)
            runCurrent()
            assertTrue(job.isCompleted)
        }
}
