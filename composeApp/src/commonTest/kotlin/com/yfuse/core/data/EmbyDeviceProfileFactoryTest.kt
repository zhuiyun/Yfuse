package com.yfuse.core.data

import com.yfuse.core.data.dto.DeviceProfileDto
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
    fun conservative_profile_advertises_bundled_local_audio_decode_without_overclaiming_hdr() {
        val profile = DeviceProfileDto.yfuseAndroid()
        val direct = profile.DirectPlayProfiles.single()
        val h264Ranges = profile.videoRanges("h264")
        val audio = direct.AudioCodec.split(',').toSet()

        assertEquals("h264", direct.VideoCodec)
        assertTrue(audio.containsAll(setOf("aac", "ac3", "eac3", "truehd", "dts", "dca")))
        assertEquals(setOf("SDR"), h264Ranges)
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
    fun dolby_profile_requires_both_display_and_decoder_and_preserves_direct_atmos_route() {
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
    fun hdr10_only_profile_allows_compatible_base_layer_but_not_dolby_only_video() {
        val profile =
            DeviceProfileDto.yfuseAndroid(
                capabilities(
                    hdr = setOf(PlaybackHdrFormat.Hdr10),
                    video = setOf(PlaybackVideoCodec.H264, PlaybackVideoCodec.Hevc),
                ),
            )
        val ranges = profile.videoRanges("hevc")

        assertTrue("HDR10" in ranges)
        assertTrue("DOVIWithHDR10" in ranges)
        assertFalse("DOVI" in ranges)
        assertFalse("DOVIWithEL" in ranges)
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
