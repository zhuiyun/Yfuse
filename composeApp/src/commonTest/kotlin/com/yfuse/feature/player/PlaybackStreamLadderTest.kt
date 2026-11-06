package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** Approved source fallbacks are independent from the selected engine identity. */
class PlaybackStreamLadderTest {
    private fun step(
        rung: PlaybackStreamRung,
        item: PlayerMediaItem? = ladderItem(),
        viewer: Boolean = false,
    ) = PlaybackFallbackLadder.nextStreamStep(rung, item, viewer)

    @Test
    fun original_hls_and_mp4_are_a_finite_ordered_source_ladder() {
        assertEquals(PlaybackStreamStep.Transcode, step(PlaybackStreamRung.Original))
        assertEquals(PlaybackStreamStep.Progressive, step(PlaybackStreamRung.Transcode))
        assertEquals(PlaybackStreamStep.InProgress, step(PlaybackStreamRung.ProgressivePending))
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Progressive))
    }

    @Test
    fun absent_and_blank_urls_are_not_playable_fallbacks() {
        assertEquals(PlaybackStreamStep.Progressive, step(PlaybackStreamRung.Original, ladderItem(hls = " ")))
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Original, ladderItem(hls = "", mp4 = " ")))
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Transcode, ladderItem(mp4 = "")))
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Original, null))
    }

    @Test
    fun unapproved_server_never_receives_a_new_transcode_request() {
        val item = ladderItem(approved = false)
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Original, item))
        assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Original, item, viewer = true))
        assertEquals(
            PlaybackStreamStep.Exhausted,
            PlaybackFallbackLadder.progressiveStreamStep(PlaybackStreamRung.Original, item),
        )
    }

    @Test
    fun original_dolby_requires_explicit_viewer_permission_even_if_server_is_available() {
        listOf(ladderItem(dolbyVision = true), ladderItem(dolbyAtmos = true)).forEach { item ->
            assertEquals(PlaybackStreamStep.Exhausted, step(PlaybackStreamRung.Original, item))
            assertEquals(PlaybackStreamStep.Transcode, step(PlaybackStreamRung.Original, item, viewer = true))
            assertEquals(
                PlaybackStreamStep.Exhausted,
                PlaybackFallbackLadder.progressiveStreamStep(PlaybackStreamRung.Original, item),
            )
        }
        assertEquals(
            PlaybackStreamStep.Progressive,
            step(PlaybackStreamRung.Original, ladderItem(hls = "", dolbyVision = true), viewer = true),
        )
    }

    @Test
    fun pending_and_completed_progressive_states_take_precedence() {
        assertEquals(
            PlaybackStreamRung.Progressive,
            PlaybackFallbackLadder.streamRung(transcoded = true, progressive = true, progressivePending = true),
        )
        assertEquals(
            PlaybackStreamRung.ProgressivePending,
            PlaybackFallbackLadder.streamRung(transcoded = true, progressive = false, progressivePending = true),
        )
    }
}
