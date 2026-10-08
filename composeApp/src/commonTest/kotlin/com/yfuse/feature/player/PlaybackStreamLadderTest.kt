package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals

/** The stream ladder inside Exo, mpv and MDK: the original file, the HLS transcode, the MP4. */
class PlaybackStreamLadderTest {
    private val manualRequest = true

    private fun assertEveryEngine(
        expected: PlaybackStreamStep,
        sets: StreamSets,
        item: PlayerMediaItem?,
        viewerRequested: Boolean = false,
    ) = forEachFallbackLadder { ladder ->
        assertEquals(expected, ladder.exoNext(sets, item, viewerRequested), "exo")
        assertEquals(expected, ladder.mpvNext(sets, item, viewerRequested), "mpv")
        assertEquals(expected, ladder.mdkNext(sets, item, viewerRequested), "mdk")
    }

    @Test
    fun the_original_file_steps_to_the_hls_transcode_first() {
        assertEveryEngine(PlaybackStreamStep.Transcode, StreamSets.original, ladderItem())
        forEachFallbackLadder { ladder ->
            assertEquals(
                PlaybackStreamStep.Transcode,
                ladder.exoAfterTransportFailure(StreamSets.original, ladderItem()),
            )
        }
    }

    @Test
    fun the_hls_transcode_steps_to_the_progressive_mp4() {
        assertEveryEngine(PlaybackStreamStep.Progressive, StreamSets.transcode, ladderItem())
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.Progressive, ladder.exoProgressive(StreamSets.transcode, ladderItem()))
            assertEquals(
                PlaybackStreamStep.Progressive,
                ladder.exoAfterTransportFailure(StreamSets.transcode, ladderItem()),
            )
        }
    }

    @Test
    fun the_progressive_mp4_is_the_last_rung() {
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.mp4, ladderItem())
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoProgressive(StreamSets.mp4, ladderItem()))
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoAfterTransportFailure(StreamSets.mp4, ladderItem()))
        }
    }

    @Test
    fun a_step_onto_the_mp4_already_under_way_counts_as_moving_on() {
        assertEveryEngine(PlaybackStreamStep.InProgress, StreamSets.pendingMp4, ladderItem())
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.InProgress, ladder.exoProgressive(StreamSets.pendingMp4, ladderItem()))
        }
    }

    @Test
    fun a_missing_hls_stream_is_skipped_and_a_missing_mp4_ends_the_ladder() {
        assertEveryEngine(PlaybackStreamStep.Progressive, StreamSets.original, ladderItem(hls = ""))
        assertEveryEngine(PlaybackStreamStep.Transcode, StreamSets.original, ladderItem(mp4 = ""))
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.transcode, ladderItem(mp4 = ""))
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, ladderItem(hls = "", mp4 = ""))
    }

    @Test
    fun a_blank_stream_url_counts_as_missing() {
        // The approval check counts a whitespace-only URL as missing; the step it allowed used to
        // pick that URL anyway, which the engine could only fail to open.
        val blankHls = ladderItem(hls = " ")
        val blankMp4 = ladderItem(mp4 = " ")
        assertEveryEngine(PlaybackStreamStep.Progressive, StreamSets.original, blankHls)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.transcode, blankMp4)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, ladderItem(hls = " ", mp4 = " "))
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.Progressive, ladder.exoAfterTransportFailure(StreamSets.original, blankHls))
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoProgressive(StreamSets.original, blankMp4))
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoProgressive(StreamSets.transcode, blankMp4))
        }
    }

    @Test
    fun leaving_the_original_file_needs_the_servers_approval_leaving_the_hls_transcode_does_not() {
        val unapproved = ladderItem(approved = false)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, unapproved)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, unapproved, manualRequest)
        assertEveryEngine(PlaybackStreamStep.Progressive, StreamSets.transcode, unapproved)
        // A version the server approved, or a negotiated transcode, is approval too.
        assertEveryEngine(
            PlaybackStreamStep.Transcode,
            StreamSets.original,
            ladderItem(approved = false, versionApproved = true),
        )
        assertEveryEngine(
            PlaybackStreamStep.Transcode,
            StreamSets.original,
            ladderItem(approved = false, playMethod = PlaybackMethod.Transcode),
        )
    }

    @Test
    fun a_local_dolby_original_leaves_only_on_the_viewers_own_request() {
        for (dolby in listOf(ladderItem(dolbyVision = true), ladderItem(dolbyAtmos = true))) {
            assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, dolby)
            assertEveryEngine(PlaybackStreamStep.Transcode, StreamSets.original, dolby, manualRequest)
        }
        // A disc image is not decoded locally as Dolby, so it is no exception.
        assertEveryEngine(
            PlaybackStreamStep.Transcode,
            StreamSets.original,
            ladderItem(dolbyVision = true, disc = true),
        )
    }

    @Test
    fun exo_keeps_a_local_dolby_original_off_the_mp4_even_on_request() {
        val dolbyWithoutHls = ladderItem(hls = "", dolbyVision = true)
        forEachFallbackLadder { ladder ->
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoNext(StreamSets.original, dolbyWithoutHls, manualRequest),
            )
            assertEquals(
                PlaybackStreamStep.Progressive,
                ladder.mpvNext(StreamSets.original, dolbyWithoutHls, manualRequest),
            )
            assertEquals(
                PlaybackStreamStep.Progressive,
                ladder.mdkNext(StreamSets.original, dolbyWithoutHls, manualRequest),
            )
        }
    }

    @Test
    fun exo_goes_straight_to_the_mp4_for_a_manifest_no_retry_can_fix() {
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.Progressive, ladder.exoProgressive(StreamSets.original, ladderItem()))
            // Straight to the MP4 or not, leaving the original file needs the server's approval.
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoProgressive(StreamSets.original, ladderItem(approved = false)),
            )
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoProgressive(StreamSets.original, ladderItem(dolbyVision = true)),
            )
            assertEquals(
                PlaybackStreamStep.Progressive,
                ladder.exoProgressive(StreamSets.transcode, ladderItem(dolbyVision = true)),
            )
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoProgressive(StreamSets.original, ladderItem(mp4 = "")),
            )
        }
    }

    @Test
    fun after_a_transport_failure_exo_takes_the_next_step_and_nothing_else() {
        forEachFallbackLadder { ladder ->
            assertEquals(
                PlaybackStreamStep.Progressive,
                ladder.exoAfterTransportFailure(StreamSets.original, ladderItem(hls = "")),
            )
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoAfterTransportFailure(StreamSets.original, ladderItem(approved = false)),
            )
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoAfterTransportFailure(StreamSets.original, ladderItem(dolbyVision = true)),
            )
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoAfterTransportFailure(StreamSets.original, ladderItem(approved = false, mp4 = "")),
            )
        }
    }

    @Test
    fun a_server_that_did_not_approve_transcoding_keeps_every_engine_on_the_original_file() {
        // Exo used to ask such a server for its MP4 anyway, straight after a malformed manifest or
        // a transport failure, and waited for a refusal before PlayerRoot could try anything else.
        val unapproved = ladderItem(approved = false)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, unapproved)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, unapproved, manualRequest)
        forEachFallbackLadder { ladder ->
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoProgressive(StreamSets.original, unapproved))
            assertEquals(PlaybackStreamStep.Exhausted, ladder.exoAfterTransportFailure(StreamSets.original, unapproved))
            assertEquals(
                PlaybackStreamStep.Exhausted,
                ladder.exoProgressive(StreamSets.original, ladderItem(approved = false, hls = "")),
            )
        }
    }

    @Test
    fun a_missing_entry_has_nothing_to_step_to() {
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, item = null)
        assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.transcode, item = null)
    }

    @Test
    fun exo_retries_a_transient_network_failure_twice_quickly_then_slower() {
        forEachFallbackLadder { ladder ->
            assertEquals(2, ladder.transientRetryLimit)
            assertEquals(500L, ladder.transientRetryDelayMs(1))
            assertEquals(1_500L, ladder.transientRetryDelayMs(2))
        }
    }
}
