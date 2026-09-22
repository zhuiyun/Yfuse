package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHandoverTest {
    @Test
    fun paused_handover_preserves_position_speed_and_pause_intent() {
        val snapshot =
            playbackHandoverSnapshot(
                state = PlaybackState(currentIndex = 2, positionMs = 12_000L, speed = 1.5f),
                currentPositionMs = 12_345L,
                playbackRequested = false,
                requestedSpeed = 1.5f,
            )

        assertEquals(2, snapshot.itemIndex)
        assertEquals(12_345L, snapshot.positionMs)
        assertEquals(1.5f, snapshot.speed)
        assertFalse(snapshot.playbackRequested)
    }

    @Test
    fun ended_media_never_restarts_during_handover() {
        val snapshot =
            playbackHandoverSnapshot(
                state = PlaybackState(ended = true),
                currentPositionMs = -1L,
                playbackRequested = true,
                requestedSpeed = Float.NaN,
            )

        assertEquals(0L, snapshot.positionMs)
        assertEquals(1f, snapshot.speed)
        assertFalse(snapshot.playbackRequested)
    }

    @Test
    fun paused_handover_enforces_the_250ms_budget() {
        val snapshot = PlaybackHandoverSnapshot(0, 10_000L, false, 1f)

        assertEquals(0L, handoverPositionErrorMs(10_250L, snapshot, 5_000L))
        assertEquals(1L, handoverPositionErrorMs(10_251L, snapshot, 5_000L))
        assertEquals(1L, handoverPositionErrorMs(9_749L, snapshot, 5_000L))
    }

    @Test
    fun playing_handover_allows_only_real_time_progression() {
        val snapshot = PlaybackHandoverSnapshot(0, 20_000L, true, 1.5f)

        assertEquals(0L, handoverPositionErrorMs(21_750L, snapshot, 1_000L))
        assertTrue(handoverPositionErrorMs(22_000L, snapshot, 1_000L) > 0L)
    }

    @Test
    fun handover_position_validation_is_one_shot_and_item_scoped() {
        val snapshot = PlaybackHandoverSnapshot(2, 20_000L, true, 1f)

        assertTrue(
            shouldValidatePlaybackHandoverPosition(
                snapshot = snapshot,
                currentItemIndex = 2,
                alreadyValidated = false,
            ),
        )
        assertFalse(
            shouldValidatePlaybackHandoverPosition(
                snapshot = snapshot,
                currentItemIndex = 3,
                alreadyValidated = false,
            ),
        )
        assertFalse(
            shouldValidatePlaybackHandoverPosition(
                snapshot = snapshot,
                currentItemIndex = 2,
                alreadyValidated = true,
            ),
        )
    }

    @Test
    fun captures_tracks_delays_and_disc_location() {
        val audio = EngineTrack("a1", "TrueHD", "eng", selected = true, codec = "truehd")
        val subtitle = EngineTrack("s1", "简体", "zho", selected = true, codec = "ass")
        val snapshot =
            playbackHandoverSnapshot(
                state =
                    PlaybackState(
                        audioTracks = listOf(audio),
                        subtitleTracks = listOf(subtitle),
                        discNavigation =
                            com.yfuse.core.playback.PlaybackDiscNavigationState(
                                titleCount = 3,
                                selectedTitleIndex = 2,
                                chapterCount = 12,
                                selectedChapterIndex = 7,
                            ),
                    ),
                currentPositionMs = 5_000L,
                playbackRequested = true,
                requestedSpeed = 1.25f,
                secondarySubtitle = TrackRestorePreference("eng", "English", "srt"),
                subtitleDelayMs = 450L,
                audioDelayMs = -120L,
            )

        assertEquals("a1", audio.id)
        assertEquals("eng", snapshot.audioTrack?.language)
        assertEquals("zho", snapshot.primarySubtitle?.language)
        assertEquals("eng", snapshot.secondarySubtitle?.language)
        assertEquals(450L, snapshot.subtitleDelayMs)
        assertEquals(-120L, snapshot.audioDelayMs)
        assertEquals(2, snapshot.discTitleIndex)
        assertEquals(7, snapshot.discChapterIndex)
    }
}
