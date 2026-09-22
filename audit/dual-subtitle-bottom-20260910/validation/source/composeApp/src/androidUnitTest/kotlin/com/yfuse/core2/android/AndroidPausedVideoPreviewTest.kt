package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPausedVideoPreviewTest {
    @Test
    fun `submitted preview recycles remaining codec output without verifying or displaying it`() {
        val preview = AndroidPausedVideoPreview()
        val trailing = YCodecOutputResult.Buffer(index = 3, presentationTimeUs = 2_400_000L, flags = 0, size = 12)
        val discarded = mutableListOf<YCodecOutputResult.Buffer>()
        preview.begin(2_300_000L)
        assertFalse(preview.recycleSubmittedOutput({ error("Preview has not been submitted") }, discarded::add))
        preview.frameSubmitted(0L)
        assertTrue(preview.recycleSubmittedOutput({ trailing }, discarded::add))
        assertEquals(listOf(trailing), discarded)
        assertTrue(preview.active)
        assertFalse(preview.recycleSubmittedOutput({ YCodecOutputResult.TryAgain }, discarded::add))
        preview.frameRendered()
        assertFalse(preview.recycleSubmittedOutput({ error("Already verified") }, discarded::add))
        assertEquals(2_300_000L, preview.takeResumePosition())
    }

    @Test
    fun `tunnel waits for actual audio progress and bounds a short clip final callback`() {
        val watchdog = AndroidTunnelVideoOutputWatchdog()
        assertFalse(watchdog.observe(true, false, true, 0L, false, 0L))
        assertFalse(watchdog.observe(true, false, true, 0L, false, 90_000_000_000L))
        assertFalse(watchdog.observe(true, false, true, 1_000L, true, 100_000_000_000L))
        assertFalse(watchdog.observe(true, false, true, 10_000_000L, true, 129_000_000_000L))
        assertTrue(watchdog.observe(true, false, true, 10_000_000L, true, 130_000_000_000L))
        assertFalse(watchdog.observe(true, true, true, 10_000_000L, true, 131_000_000_000L))
    }

    @Test
    fun `network wait and a new generation cannot age the tunnel renderer deadline`() {
        val watchdog = AndroidTunnelVideoOutputWatchdog()
        watchdog.observe(true, false, true, 0L, false, 0L)
        watchdog.observe(true, false, true, 1_000L, false, 1_000_000L)
        assertFalse(watchdog.observe(true, false, true, 1_000L, false, 60_000_000_000L))
        assertFalse(watchdog.observe(true, false, true, 2_000L, false, 61_000_000_000L))
        watchdog.reset()
        assertFalse(watchdog.observe(true, false, true, 20_000_000L, true, 100_000_000_000L))
        assertFalse(watchdog.observe(true, false, false, 21_000_000L, true, 150_000_000_000L))
    }

    @Test
    fun `missing static frame callback settles unconfirmed without a renderer failure`() {
        val preview = AndroidPausedVideoPreview()
        preview.begin(0L)
        assertFalse(preview.finishCallbackWait(90_000_000_000L))
        preview.frameSubmitted(100_000_000_000L)
        assertFalse(preview.finishCallbackWait(100_999_999_999L))
        assertTrue(preview.finishCallbackWait(101_000_000_000L))
        assertEquals(AndroidPausedPreviewState.SubmittedUnconfirmed, preview.state)
        assertFalse(preview.active)
        assertTrue(preview.submitted)
        assertTrue(preview.resumePending)
        assertFalse(preview.finishCallbackWait(200_000_000_000L))
        assertEquals(0L, preview.takeResumePosition())
    }

    @Test
    fun `late real callback confirms the same submitted preview after waiting stops`() {
        val preview = AndroidPausedVideoPreview()
        preview.begin(2_300_000L)
        preview.frameSubmitted(0L)
        assertTrue(preview.finishCallbackWait(1_000_000_000L))
        preview.frameRendered()
        assertEquals(AndroidPausedPreviewState.Rendered, preview.state)
        assertFalse(preview.active)
        assertFalse(preview.submittedUnconfirmed)
        assertEquals(2_300_000L, preview.takeResumePosition())
    }

    @Test
    fun `new seek and actual callback reset the bounded callback wait`() {
        val preview = AndroidPausedVideoPreview()
        preview.begin(0L)
        preview.frameSubmitted(0L)
        preview.begin(10_000_000L)
        assertFalse(preview.finishCallbackWait(200_000_000_000L))
        preview.frameSubmitted(200_000_000_000L)
        preview.frameRendered()
        assertFalse(preview.finishCallbackWait(230_000_000_000L))
        assertTrue(preview.resumePending)
        preview.takeResumePosition()
        assertFalse(preview.resumePending)
    }

    @Test
    fun `preview waits for actual output and restores discarded audio source on resume`() {
        val preview = AndroidPausedVideoPreview()
        preview.begin(5_000_000L)
        preview.frameRendered()
        assertTrue(preview.active)
        preview.frameSubmitted()
        assertTrue(preview.active)
        assertTrue(preview.submitted)
        preview.frameRendered()
        assertFalse(preview.active)
        assertEquals(5_000_000L, preview.takeResumePosition())
        assertFalse(preview.submitted)
        assertNull(preview.takeResumePosition())
    }

    @Test
    fun `new seek supersedes previous preview and release clears pending resume`() {
        val preview = AndroidPausedVideoPreview()
        preview.begin(1_000_000L)
        preview.frameSubmitted()
        preview.begin(9_000_000L)
        assertFalse(preview.submitted)
        assertEquals(9_000_000L, preview.takeResumePosition())
        preview.begin(2_000_000L)
        preview.clear()
        assertFalse(preview.active)
        assertNull(preview.takeResumePosition())
    }
}
