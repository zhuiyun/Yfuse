package com.yfuse.core2.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeDirectOutputLifecycleTest {
    @Test
    fun ordinary_video_startup_keeps_the_first_frame_gate_and_suspension_requires_audio() {
        val output = NativeDirectOutputLifecycle()
        assertFalse(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        assertTrue(output.allowsAudioPump(hasVideo = false, hasAudio = true))
        output.setVideoSuspended(true, audioCarryingPlayback = false)
        assertTrue(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        assertFalse(output.allowsAudioPump(hasVideo = true, hasAudio = false))
        output.setVideoSuspended(false, audioCarryingPlayback = false)
        assertFalse(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        output.videoRendered()
        assertTrue(output.allowsAudioPump(hasVideo = true, hasAudio = true))
    }

    @Test
    fun a_background_reopen_or_next_child_starts_audio_without_reusing_old_video_evidence() {
        val oldSession = NativeDirectOutputLifecycle()
        oldSession.videoRendered()
        oldSession.setVideoSuspended(true, audioCarryingPlayback = true)
        oldSession.resetMedia() // Retry/SelectItem keeps the user's background intent.
        assertFalse(oldSession.videoEstablished)
        assertTrue(oldSession.videoSuspended)
        assertTrue(oldSession.allowsAudioPump(hasVideo = true, hasAudio = true))

        val nextSession = NativeDirectOutputLifecycle()
        nextSession.setVideoSuspended(oldSession.videoSuspended, audioCarryingPlayback = false)
        assertFalse(nextSession.videoEstablished)
        assertTrue(nextSession.allowsAudioPump(hasVideo = true, hasAudio = true))
    }

    @Test
    fun foreground_before_surface_keeps_audio_and_waits_for_the_next_video_sync_sample() {
        val output = NativeDirectOutputLifecycle()
        output.setVideoSuspended(true, audioCarryingPlayback = false)
        assertTrue(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        output.setVideoSuspended(false, audioCarryingPlayback = true)
        assertFalse(output.videoEstablished)
        assertTrue(output.allowsAudioPump(hasVideo = true, hasAudio = true))

        // The session takes its audio-continuing Surface branch, preserving the shared demux.
        output.awaitVideoSync()
        repeat(3) { assertTrue(output.discardUntilVideoSync(syncSample = false)) }
        assertEquals(3, output.skippedVideoSamples)
        assertTrue(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        assertFalse(output.discardUntilVideoSync(syncSample = true))
        assertFalse(output.awaitingVideoSync)
        assertFalse(output.discardUntilVideoSync(syncSample = false))
        output.videoRendered()
        assertTrue(output.videoEstablished)

        // A later ordinary next item must not inherit either output proof or the audio exception.
        output.resetMedia()
        assertFalse(output.allowsAudioPump(hasVideo = true, hasAudio = true))
        assertFalse(output.awaitingVideoSync)
    }

    @Test
    fun suspension_commands_remain_ordered_across_prepare_item_switch_and_surface_attachment() {
        val suspendVideo = AndroidNativeDirectYPlayer.Command.SetVideoSuspended(true)
        val restoreVideo = AndroidNativeDirectYPlayer.Command.SetVideoSuspended(false)
        val prepare = AndroidNativeDirectYPlayer.Command.Prepare
        val nextItem = AndroidNativeDirectYPlayer.Command.SelectItem(1)
        val detached = AndroidNativeDirectYPlayer.Command.SetVideoOutput(null)
        assertEquals(
            listOf(suspendVideo, prepare, nextItem, restoreVideo, detached),
            coalesceNativeDirectCommands(
                listOf(suspendVideo, suspendVideo, prepare, nextItem, restoreVideo, detached),
            ),
        )
    }
}
