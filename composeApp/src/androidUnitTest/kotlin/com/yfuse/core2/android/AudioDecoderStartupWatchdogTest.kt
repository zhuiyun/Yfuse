package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDecoderStartupWatchdogTest {
    @Test fun inputStarvationDoesNotBlameTheDecoder() {
        val watchdog = AudioDecoderStartupWatchdog()
        repeat(3) { watchdog.inputQueued() }
        assertFalse(watchdog.shouldFallback(0L, eligible = true))
        assertFalse(watchdog.shouldFallback(30_000_000_000L, eligible = true))
    }

    @Test fun acceptedAudioWithoutOutputRecoversOnceAfterThreeSeconds() {
        val watchdog = fedWatchdog()
        assertFalse(watchdog.shouldFallback(0L, eligible = true))
        assertFalse(watchdog.shouldFallback(2_999_999_999L, eligible = true))
        assertTrue(watchdog.shouldFallback(3_000_000_000L, eligible = true))
        assertFalse(watchdog.shouldFallback(30_000_000_000L, eligible = true))
    }

    @Test fun healthyOutputAndPausingCancelTheStartupWait() {
        val watchdog = fedWatchdog()
        assertFalse(watchdog.shouldFallback(0L, eligible = true))
        assertFalse(watchdog.shouldFallback(2_000_000_000L, eligible = false))
        assertFalse(watchdog.shouldFallback(30_000_000_000L, eligible = false))
        assertFalse(watchdog.shouldFallback(31_000_000_000L, eligible = true))
        watchdog.pause()
        assertFalse(watchdog.shouldFallback(60_000_000_000L, eligible = true))
        assertTrue(watchdog.shouldFallback(63_000_000_000L, eligible = true))
    }

    @Test fun seekOrTrackReplacementRequiresFreshInput() {
        val watchdog = fedWatchdog()
        assertFalse(watchdog.shouldFallback(0L, eligible = true))
        assertTrue(watchdog.shouldFallback(3_000_000_000L, eligible = true))
        watchdog.reset()
        assertFalse(watchdog.shouldFallback(30_000_000_000L, eligible = true))
        repeat(4) { watchdog.inputQueued() }
        assertFalse(watchdog.shouldFallback(31_000_000_000L, eligible = true))
        assertTrue(watchdog.shouldFallback(34_000_000_000L, eligible = true))
    }

    private fun fedWatchdog() = AudioDecoderStartupWatchdog().apply { repeat(4) { inputQueued() } }
}
