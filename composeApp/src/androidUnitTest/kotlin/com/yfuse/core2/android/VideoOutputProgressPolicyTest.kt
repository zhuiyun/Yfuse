package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoOutputProgressPolicyTest {
    @Test
    fun decoderNeedsAcceptedInputAndActiveTimeBeforeRecovery() {
        val watchdog = VideoFirstOutputWatchdog(timeoutNs = 100L)
        assertFalse(watchdog.stalled(1000L, active = true, outputVerified = false, masterPositionUs = 0L))
        watchdog.inputQueued(0L)
        assertFalse(watchdog.stalled(2000L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(2099L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertTrue(watchdog.stalled(2100L, active = true, outputVerified = false, masterPositionUs = 0L))
    }

    @Test
    fun pauseOrTransportStarvationCannotConsumeTheDecoderBudget() {
        val watchdog = VideoFirstOutputWatchdog(timeoutNs = 100L)
        watchdog.inputQueued(0L)
        assertFalse(watchdog.stalled(0L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(90L, active = false, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(1000L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(1099L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertTrue(watchdog.stalled(1100L, active = true, outputVerified = false, masterPositionUs = 0L))
    }

    @Test
    fun verifiedFrameAndNewOutputEpochClearTheTimeout() {
        val watchdog = VideoFirstOutputWatchdog(timeoutNs = 100L)
        watchdog.inputQueued(0L)
        assertFalse(watchdog.stalled(0L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(1000L, active = true, outputVerified = true, masterPositionUs = 0L))
        watchdog.reset()
        assertFalse(watchdog.stalled(2000L, active = true, outputVerified = false, masterPositionUs = 0L))
    }

    @Test
    fun authoredVideoDelayDoesNotTriggerDecoderRecoveryBeforeItsFirstPacketIsDue() {
        val watchdog = VideoFirstOutputWatchdog(timeoutNs = 100L)
        watchdog.inputQueued(60_000_000L)
        assertFalse(watchdog.stalled(0L, active = true, outputVerified = false, masterPositionUs = 0L))
        assertFalse(watchdog.stalled(1000L, active = true, outputVerified = false, masterPositionUs = 30_000_000L))
        assertFalse(watchdog.stalled(2000L, active = true, outputVerified = false, masterPositionUs = 60_000_000L))
        assertTrue(watchdog.stalled(2100L, active = true, outputVerified = false, masterPositionUs = 60_000_100L))
    }

    @Test
    fun softwareOutputDoesNotFreezeWhenEveryDecodedFrameIsLate() {
        val now = 1_000_000_000L
        assertEquals(
            YVideoFrameReleaseDecision.Render(now),
            preserveSoftwareVideoProgress(YVideoFrameReleaseDecision.Drop, now - 250_000_000L, now),
        )
        assertEquals(
            YVideoFrameReleaseDecision.Drop,
            preserveSoftwareVideoProgress(YVideoFrameReleaseDecision.Drop, now - 249_999_999L, now),
        )
        assertEquals(
            YVideoFrameReleaseDecision.Hold,
            preserveSoftwareVideoProgress(YVideoFrameReleaseDecision.Hold, 0L, now),
        )
    }

    @Test
    fun nativeDiscardSkipsOnlyOldFramesAndNeverAdvancesAPausedPreview() {
        val now = 1_000_000_000L
        assertEquals(900_000L, softwareVideoMinimumPresentationUs(0L, 1_000_000L, now - 1L, now, false))
        assertEquals(950_000L, softwareVideoMinimumPresentationUs(950_000L, 1_000_000L, now - 1L, now, false))
        assertEquals(0L, softwareVideoMinimumPresentationUs(0L, 1_000_000L, 0L, now, false))
        assertEquals(200_000L, softwareVideoMinimumPresentationUs(200_000L, 1_000_000L, now - 1L, now, true))
    }
}
