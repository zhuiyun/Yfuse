package com.yfuse.core.data

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.VideoStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaVersionSelectionTest {
    @Test
    fun hdr_first_ignores_server_media_source_order() {
        val dolby = version("dolby", range = "DOVI", dolbyProfile = 5)
        val hdr = version("hdr", range = "HDR10")

        assertEquals(
            "hdr",
            listOf(dolby, hdr)
                .preferredVersion(MediaVersionPreference.HdrFirst)
                ?.id,
        )
    }

    @Test
    fun hdr_first_falls_back_to_dolby_then_sdr() {
        val dolby = version("dolby", range = "DOVI", dolbyProfile = 8)
        val sdr = version("sdr", range = null)

        assertEquals(
            "dolby",
            listOf(sdr, dolby)
                .preferredVersion(MediaVersionPreference.HdrFirst)
                ?.id,
        )
        assertEquals(
            "sdr",
            listOf(sdr)
                .preferredVersion(MediaVersionPreference.HdrFirst)
                ?.id,
        )
    }

    @Test
    fun explicit_episode_choice_wins_over_automatic_preference() {
        val dolby = version("dolby", range = "DOVI", dolbyProfile = 5)
        val hdr = version("hdr", range = "HDR10")

        assertEquals(
            "dolby",
            listOf(dolby, hdr)
                .preferredVersion(
                    preference = MediaVersionPreference.HdrFirst,
                    explicitVersionId = "dolby",
                )?.id,
        )
    }

    @Test
    fun dolby_first_and_highest_quality_are_deterministic() {
        val dolby = version("dolby", range = "DOVI", dolbyProfile = 8, bitrate = 30_000_000)
        val hdr = version("hdr", range = "HDR10", bitrate = 50_000_000)

        assertEquals(
            "dolby",
            listOf(hdr, dolby)
                .preferredVersion(MediaVersionPreference.DolbyVisionFirst)
                ?.id,
        )
        assertEquals(
            "hdr",
            listOf(dolby, hdr)
                .preferredVersion(MediaVersionPreference.HighestQuality)
                ?.id,
        )
    }

    private fun version(
        id: String,
        range: String?,
        dolbyProfile: Int? = null,
        bitrate: Int = 40_000_000,
    ) = MediaVersion(
        id = id,
        name = id,
        container = "mkv",
        sizeBytes = null,
        bitrateBps = bitrate,
        videoCodec = "hevc",
        videoHeight = 2_160,
        videoRange = range,
        video =
            VideoStreamInfo(
                width = 3_840,
                height = 2_160,
                videoRange = range,
                dolbyProfile = dolbyProfile,
            ),
    )
}
