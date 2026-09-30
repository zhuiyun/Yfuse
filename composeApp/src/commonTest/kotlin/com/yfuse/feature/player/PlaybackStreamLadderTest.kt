package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackMethod
import kotlin.test.Test
import kotlin.test.assertEquals

/** The stream ladder inside Exo, mpv and MDK: the original file, the HLS transcode, the MP4. */
class PlaybackStreamLadderTest {
    private val manualRequest = "用户手动选择服务器转码"

    private fun assertEveryEngine(
        expected: PlaybackStreamStep,
        sets: StreamSets,
        item: PlayerMediaItem?,
        reason: String? = null,
    ) = forEachFallbackLadder { ladder ->
        assertEquals(expected, ladder.exoNext(sets, item, reason), "exo")
        assertEquals(expected, ladder.mpvNext(sets, item, reason), "mpv")
        assertEquals(expected, ladder.mdkNext(sets, item, reason), "mdk")
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
            assertEveryEngine(PlaybackStreamStep.Exhausted, StreamSets.original, dolby, "解码失败")
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
            // The direct entry asks for no approval; only the local Dolby rule stops it.
            assertEquals(
                PlaybackStreamStep.Progressive,
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
    fun after_a_transport_failure_exo_takes_the_mp4_when_the_next_step_is_refused() {
        forEachFallbackLadder { ladder ->
            assertEquals(
                PlaybackStreamStep.Progressive,
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
