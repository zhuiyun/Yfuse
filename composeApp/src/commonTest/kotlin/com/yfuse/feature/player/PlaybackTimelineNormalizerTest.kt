package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTimelineNormalizerTest {
    @Test
    fun backend_timestamp_origin_is_removed_and_seek_adds_it_back() {
        val normalizer =
            PlaybackTimelineNormalizer(
                PlaybackTimelineAnchor(itemIndex = 0, positionMs = 0L),
            )

        val first =
            normalizer.normalize(
                playableState(
                    positionMs = 1_800L,
                    bufferedPositionMs = 6_800L,
                ),
            )
        assertEquals(0L, first.positionMs)
        assertEquals(5_000L, first.bufferedPositionMs)

        val later =
            normalizer.normalize(
                playableState(
                    positionMs = 11_800L,
                    bufferedPositionMs = 16_800L,
                ),
            )
        assertEquals(10_000L, later.positionMs)
        assertEquals(15_000L, later.bufferedPositionMs)

        assertEquals(
            31_800L,
            normalizer.backendPositionForSeek(
                canonicalPositionMs = 30_000L,
                state = playableState(positionMs = 11_800L),
            ),
        )
    }

    @Test
    fun resume_position_does_not_jump_to_zero_while_backend_seek_is_pending() {
        val normalizer =
            PlaybackTimelineNormalizer(
                PlaybackTimelineAnchor(itemIndex = 0, positionMs = 60_000L),
            )

        val preparing =
            normalizer.normalize(
                PlaybackState(
                    buffering = true,
                    positionMs = 0L,
                    durationMs = 120_000L,
                ),
            )
        assertEquals(60_000L, preparing.positionMs)

        val ready = normalizer.normalize(playableState(positionMs = 61_800L))
        assertEquals(60_000L, ready.positionMs)

        val advanced = normalizer.normalize(playableState(positionMs = 66_800L))
        assertEquals(65_000L, advanced.positionMs)
    }

    @Test
    fun direct_to_transcode_recalibrates_without_changing_product_position() {
        val normalizer =
            PlaybackTimelineNormalizer(
                PlaybackTimelineAnchor(itemIndex = 0, positionMs = 0L),
            )

        normalizer.normalize(playableState(positionMs = 1_800L))
        val direct = normalizer.normalize(playableState(positionMs = 51_800L))
        assertEquals(50_000L, direct.positionMs)

        val switching =
            normalizer.normalize(
                PlaybackState(
                    playing = false,
                    buffering = true,
                    positionMs = 51_800L,
                    durationMs = 120_000L,
                    transcoding = true,
                ),
            )
        assertEquals(50_000L, switching.positionMs)

        val transcoded =
            normalizer.normalize(
                playableState(
                    positionMs = 50_900L,
                    transcoding = true,
                ),
            )
        assertEquals(50_000L, transcoded.positionMs)
    }

    @Test
    fun selecting_another_item_resets_the_canonical_timeline() {
        val normalizer =
            PlaybackTimelineNormalizer(
                PlaybackTimelineAnchor(itemIndex = 0, positionMs = 0L),
            )
        normalizer.normalize(playableState(positionMs = 1_800L))
        normalizer.normalize(playableState(positionMs = 31_800L))

        normalizer.selectItem(1)
        val preparing =
            normalizer.normalize(
                PlaybackState(
                    currentIndex = 1,
                    buffering = true,
                    positionMs = 9_000L,
                    durationMs = 120_000L,
                ),
            )

        assertEquals(0L, preparing.positionMs)
    }

    @Test
    fun ended_state_still_reports_the_full_duration() {
        val normalizer =
            PlaybackTimelineNormalizer(
                PlaybackTimelineAnchor(itemIndex = 0, positionMs = 0L),
            )
        normalizer.normalize(playableState(positionMs = 1_800L))

        val ended =
            normalizer.normalize(
                playableState(positionMs = 121_800L).copy(
                    playing = false,
                    ended = true,
                    durationMs = 120_000L,
                ),
            )

        assertEquals(120_000L, ended.positionMs)
    }

    private fun playableState(
        positionMs: Long,
        bufferedPositionMs: Long = positionMs,
        transcoding: Boolean = false,
    ): PlaybackState =
        PlaybackState(
            playing = true,
            buffering = false,
            positionMs = positionMs,
            durationMs = 120_000L,
            bufferedPositionMs = bufferedPositionMs,
            transcoding = transcoding,
            diagnostics =
                PlaybackDiagnostics(
                    videoReadiness = PlaybackOutputReadiness.Rendering,
                ),
        )
}
