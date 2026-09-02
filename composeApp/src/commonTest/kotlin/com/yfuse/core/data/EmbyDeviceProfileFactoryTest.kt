package com.yfuse.core.data

import com.yfuse.core.data.dto.DeviceProfileDto
import com.yfuse.core.data.dto.PlaybackInfoRequestDto
import com.yfuse.core.data.dto.YFUSE_MAX_STREAMING_BITRATE_BPS
import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackAudioRoute
import com.yfuse.core.playback.PlaybackDeviceCapabilities
import com.yfuse.core.playback.PlaybackHdrFormat
import com.yfuse.core.playback.PlaybackVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbyDeviceProfileFactoryTest {
    @Test
    fun conservative_profile_does_not_claim_unavailable_dolby_vision() {
        val profile = DeviceProfileDto.yfuseAndroid()
        val direct = profile.DirectPlayProfiles.single()
        val codecs = direct.VideoCodec.split(',').toSet()
        val hevcRanges = profile.videoRanges("hevc")
        val audio = direct.AudioCodec.split(',').toSet()

        assertTrue(codecs.containsAll(setOf("h264", "hevc")))
        assertTrue(audio.containsAll(setOf("aac", "ac3", "eac3", "truehd", "dts", "dca")))
        assertTrue(hevcRanges.containsAll(setOf("SDR", "HDR10", "HLG")))
        assertFalse(hevcRanges.any { it.startsWith("DOVI") })
        assertEquals("8", profile.audioChannelLimit())
        assertEquals("2", profile.TranscodingProfiles.first().MaxAudioChannels)
    }

    @Test
    fun disc_images_are_never_advertised_as_linear_direct_play_containers() {
        val containers =
            DeviceProfileDto
                .yfuseAndroid()
                .DirectPlayProfiles
                .single()
                .Container
                .split(',')
                .map { it.lowercase() }

        assertFalse("iso" in containers)
        assertFalse("dvd" in containers)
        assertFalse("bluray" in containers)
        assertFalse("bdmv" in containers)
    }

    @Test
    fun dolby_profile_preserves_native_output_and_direct_atmos_route() {
        val profile =
            DeviceProfileDto.yfuseAndroid(
                capabilities(
                    hdr = setOf(PlaybackHdrFormat.Hdr10, PlaybackHdrFormat.DolbyVision),
                    video =
                        setOf(
                            PlaybackVideoCodec.H264,
                            PlaybackVideoCodec.Hevc,
                            PlaybackVideoCodec.DolbyVision,
                        ),
                    audio = setOf(PlaybackAudioCodec.Aac),
                    directAudio = setOf(PlaybackAudioCodec.Eac3Joc, PlaybackAudioCodec.TrueHd),
                    channels = 8,
                ),
            )
        val direct = profile.DirectPlayProfiles.single()
        val hevcRanges = profile.videoRanges("hevc")

        assertTrue("hevc" in direct.VideoCodec.split(','))
        assertTrue("eac3" in direct.AudioCodec.split(','))
        assertTrue("truehd" in direct.AudioCodec.split(','))
        assertTrue("DOVI" in hevcRanges)
        assertTrue("DOVIWithEL" in hevcRanges)
        assertTrue("DOVIWithHDR10" in hevcRanges)
        assertEquals("8", profile.TranscodingProfiles.first().MaxAudioChannels)
    }

    @Test
    fun hdr10_only_panel_does_not_claim_dolby_vision_without_an_output_path() {
        val profile =
            DeviceProfileDto.yfuseAndroid(
                capabilities(
                    hdr = setOf(PlaybackHdrFormat.Hdr10),
                    video = setOf(PlaybackVideoCodec.H264, PlaybackVideoCodec.Hevc),
                ),
            )
        val ranges = profile.videoRanges("hevc")

        assertTrue("HDR10" in ranges)
        assertFalse(ranges.any { it.startsWith("DOVI") })
    }

    @Test
    fun sdr_panel_does_not_claim_dolby_vision_without_an_output_path() {
        val profile = DeviceProfileDto.yfuseAndroid(capabilities())
        val ranges = profile.videoRanges("hevc")
        val directVideoCodecs =
            profile.DirectPlayProfiles
                .single()
                .VideoCodec
                .split(',')

        assertTrue("hevc" in directVideoCodecs)
        assertFalse(ranges.any { it.startsWith("DOVI") })
        assertFalse("DOVIInvalid" in ranges)
        assertTrue("HDR10" in ranges)
    }

    @Test
    fun playback_info_ceiling_does_not_force_uhd_remux_transcoding() {
        val profile = DeviceProfileDto.yfuseAndroid()
        val request =
            PlaybackInfoRequestDto(
                Id = "movie",
                UserId = "user",
                DeviceProfile = profile,
                MaxStreamingBitrate = YFUSE_MAX_STREAMING_BITRATE_BPS,
            )

        assertEquals(1_000_000_000L, YFUSE_MAX_STREAMING_BITRATE_BPS)
        assertEquals(YFUSE_MAX_STREAMING_BITRATE_BPS, profile.MaxStreamingBitrate)
        assertEquals(YFUSE_MAX_STREAMING_BITRATE_BPS, request.MaxStreamingBitrate)
    }

    private fun capabilities(
        hdr: Set<PlaybackHdrFormat> = emptySet(),
        video: Set<PlaybackVideoCodec> = setOf(PlaybackVideoCodec.H264),
        audio: Set<PlaybackAudioCodec> = setOf(PlaybackAudioCodec.Aac),
        directAudio: Set<PlaybackAudioCodec> = emptySet(),
        channels: Int = 2,
    ): PlaybackDeviceCapabilities {
        val hdrDecoders =
            buildMap {
                if (PlaybackVideoCodec.Hevc in video) {
                    put(
                        PlaybackVideoCodec.Hevc,
                        hdr.filterTo(mutableSetOf()) { it != PlaybackHdrFormat.DolbyVision },
                    )
                }
                if (PlaybackVideoCodec.DolbyVision in video) {
                    put(PlaybackVideoCodec.DolbyVision, setOf(PlaybackHdrFormat.DolbyVision))
                }
            }
        return PlaybackDeviceCapabilities(
            hdrFormats = hdr,
            videoDecoders = video,
            hdrDecoders = hdrDecoders,
            audioDecoders = audio,
            directAudioFormats = directAudio,
            dolbyVisionCodecProfiles = emptySet(),
            dolbyVisionBaseCodecs =
                setOf(PlaybackVideoCodec.Hevc)
                    .takeIf { PlaybackVideoCodec.DolbyVision in video }
                    .orEmpty(),
            audioRoutes = setOf(PlaybackAudioRoute.BuiltIn),
            maxAudioChannels = channels,
        )
    }

    private fun DeviceProfileDto.videoRanges(codec: String): Set<String> =
        CodecProfiles
            .single { it.Type == "Video" && it.Codec == codec }
            .Conditions
            .single { it.Property == "VideoRangeType" }
            .Value
            .split('|')
            .toSet()

    private fun DeviceProfileDto.audioChannelLimit(): String =
        CodecProfiles
            .single { it.Type == "VideoAudio" }
            .Conditions
            .single { it.Property == "AudioChannels" }
            .Value
}
