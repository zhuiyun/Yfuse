package com.yfuse.core.model

import com.yfuse.core.data.dto.dolbyProfileFromCodecTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DolbyVisionTest {

    private fun version(
        videoRange: String? = null,
        codec: String? = null,
        profile: String? = null,
        dolbyProfile: Int? = null,
        baseLayer: Int? = null,
    ) = MediaVersion(
        id = "v1",
        name = "文件",
        container = "mkv",
        sizeBytes = null,
        bitrateBps = null,
        videoCodec = codec,
        videoHeight = 2160,
        videoRange = videoRange,
        video = VideoStreamInfo(
            codec = codec,
            profile = profile,
            dolbyProfile = dolbyProfile,
            dolbyBaseLayerCompatibility = baseLayer,
        ),
    )

    @Test
    fun the_profile_is_read_from_the_codec_tag_when_the_server_does_not_report_it() {
        assertEquals(5, dolbyProfileFromCodecTag("dvhe.05.06", null))
        assertEquals(8, dolbyProfileFromCodecTag("dvh1.08.09", null))
        // Emby puts the tag in Profile on some libraries and in Codec on others.
        assertEquals(7, dolbyProfileFromCodecTag("hevc", "dvhe.07.06"))
        assertNull(dolbyProfileFromCodecTag("hevc", "Main 10"))
    }

    @Test
    fun the_servers_own_field_wins_over_the_codec_tag() {
        val subject = version(videoRange = "DOVI", codec = "dvhe.08.06", dolbyProfile = 5)

        assertEquals(5, subject.dolbyProfile)
    }

    @Test
    fun profile_five_needs_a_dolby_decoder_and_profile_eight_does_not() {
        // P5 is IPT-PQ-C2 all the way down: no ordinary decoder can render it correctly.
        assertTrue(version(videoRange = "DOVI", dolbyProfile = 5).needsDolbyCapableDecoder)
        // P8 carries an HDR10 base layer any HEVC decoder can read.
        assertFalse(version(videoRange = "DOVI", dolbyProfile = 8).needsDolbyCapableDecoder)
    }

    @Test
    fun a_server_declaring_no_compatible_base_layer_is_believed() {
        val subject = version(videoRange = "DOVI", dolbyProfile = 7, baseLayer = 0)

        assertTrue(subject.needsDolbyCapableDecoder)
        // 1 is an HDR10 base layer, which every engine can fall back to.
        assertFalse(version(videoRange = "DOVI", dolbyProfile = 7, baseLayer = 1).needsDolbyCapableDecoder)
    }

    @Test
    fun nothing_about_dolby_applies_to_a_file_that_is_not_dolby() {
        val sdr = version(videoRange = "SDR", codec = "hevc")

        assertFalse(sdr.isDolbyVision)
        assertNull(sdr.dolbyProfile)
        assertFalse(sdr.needsDolbyCapableDecoder)
        assertEquals("SDR", sdr.rangeLabel)
    }

    @Test
    fun the_range_label_names_the_profile_because_it_decides_playability() {
        assertEquals("Dolby Vision P5", version(videoRange = "DOVI", dolbyProfile = 5).rangeLabel)
        // Dolby with no profile reported still says what it is.
        assertEquals("Dolby Vision", version(videoRange = "Dolby Vision").rangeLabel)
        assertEquals("HDR10", version(videoRange = "HDR10").rangeLabel)
    }
}
