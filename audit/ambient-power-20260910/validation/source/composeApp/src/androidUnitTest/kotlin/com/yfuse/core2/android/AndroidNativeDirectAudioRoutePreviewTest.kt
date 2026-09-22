package com.yfuse.core2.android

import com.yfuse.core2.api.YPlayerDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidNativeDirectAudioRoutePreviewTest {
    @Test
    fun `audio route event preserves the rendered paused frame but invalidates audio proof`() {
        val before =
            YPlayerDiagnostics(
                videoOutputVerified = true,
                audioOutputVerified = true,
                dolbyVisionOutput = true,
                outputEvidenceGeneration = 4L,
            )
        val paused = invalidateNativeDirectAudioRoute(before, preservePausedVideo = true)
        assertTrue(paused.videoOutputVerified)
        assertTrue(paused.dolbyVisionOutput)
        assertFalse(paused.audioOutputVerified)
        assertEquals(5L, paused.outputEvidenceGeneration)
        val playing = invalidateNativeDirectAudioRoute(before, preservePausedVideo = false)
        assertFalse(playing.videoOutputVerified)
        assertFalse(playing.audioOutputVerified)
    }

    @Test
    fun `an in-flight single preview frame can still prove output after an audio-only event`() {
        val preview = AndroidPausedVideoPreview()
        val epoch = AndroidVideoOutputEpoch()
        val generation = epoch.reset(100L)
        preview.begin(2_300_000L)
        epoch.submitted(2_300_000L)
        preview.frameSubmitted(110L)
        val changed = invalidateNativeDirectAudioRoute(YPlayerDiagnostics(), preservePausedVideo = true)
        assertFalse(changed.videoOutputVerified)
        assertTrue(epoch.rendered(generation, 2_300_000L, 120L) { preview.frameRendered() })
        assertFalse(preview.active)
        assertTrue(preview.resumePending)
    }
}
