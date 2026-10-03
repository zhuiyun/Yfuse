package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackEngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSessionStateTest {
    private val secondary = TrackRestorePreference(language = "eng", label = "English", codec = "subrip")

    @Test
    fun a_rebuild_resumes_with_the_viewers_speed_second_subtitle_and_delays() {
        val choices = PlayerViewerChoices(PlaybackEngineSelection.Auto)
        choices.requestedPlaybackSpeed = 1.5f
        choices.secondarySubtitleRestore = secondary
        choices.subtitleControls = choices.subtitleControls.copy(offsetMs = -300L)
        choices.audioControls = choices.audioControls.copy(delayMs = 120L)

        val snapshot =
            choices.handover(
                // The engine's own report of the rate is not what the viewer asked for.
                state = PlaybackState(currentIndex = 3, positionMs = 60_000L, speed = 1f),
                positionMs = 61_000L,
                playbackRequested = true,
            )

        assertEquals(3, snapshot.itemIndex)
        assertEquals(61_000L, snapshot.positionMs)
        assertTrue(snapshot.playbackRequested)
        assertEquals(1.5f, snapshot.speed)
        assertEquals(secondary, snapshot.secondarySubtitle)
        assertEquals(-300L, snapshot.subtitleDelayMs)
        assertEquals(120L, snapshot.audioDelayMs)
    }

    @Test
    fun a_fresh_session_hands_over_plain_defaults() {
        val snapshot =
            PlayerViewerChoices(PlaybackEngineSelection.Auto).handover(
                state = PlaybackState(ended = true),
                positionMs = 5_000L,
                playbackRequested = true,
            )

        assertEquals(1f, snapshot.speed)
        assertNull(snapshot.secondarySubtitle)
        assertEquals(0L, snapshot.subtitleDelayMs)
        assertEquals(0L, snapshot.audioDelayMs)
        // Ended media never restarts on the rebuilt engine.
        assertFalse(snapshot.playbackRequested)
    }

    @Test
    fun a_new_build_starts_at_generation_zero_with_the_trial_allowed() {
        val resume =
            PlaybackHandoverSnapshot(
                itemIndex = 1,
                positionMs = 90_000L,
                playbackRequested = false,
                speed = 1f,
            )
        val build = PlayerEngineBuild(PlayerEngine.Mpv, DecoderMode.Hardware, resume)

        assertEquals(PlayerEngine.Mpv, build.kind)
        assertEquals(DecoderMode.Hardware, build.effectiveDecoderMode)
        assertEquals(resume, build.resume)
        assertEquals(0, build.engineGeneration)
        assertEquals(0, build.runtimeSessionGeneration)
        assertFalse(build.core2DisabledForSession)
    }
}
