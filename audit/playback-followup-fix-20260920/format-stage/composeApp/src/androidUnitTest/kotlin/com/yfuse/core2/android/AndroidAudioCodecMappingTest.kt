package com.yfuse.core2.android

import com.yfuse.core2.capability.YAudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAudioCodecMappingTest {
    @Test
    fun multi_track_scalar_metadata_never_supplies_another_tracks_missing_geometry() {
        assertEquals(2, resolveNativeDirectAudioChannelCount(YAudioCodec.Aac, 0, 8, sourceAudioTrackCount = 2))
        assertEquals(48_000, resolveNativeDirectAudioSampleRate(0, 96_000, sourceAudioTrackCount = 2))
        assertEquals(2, resolveNativeDirectAudioChannelCount(YAudioCodec.Aac, 0, 8, sourceAudioTrackCount = 0))
        assertEquals(8, resolveNativeDirectAudioChannelCount(YAudioCodec.Aac, 0, 8, sourceAudioTrackCount = 1))
        assertEquals(96_000, resolveNativeDirectAudioSampleRate(0, 96_000, sourceAudioTrackCount = 1))
        assertEquals(1, resolveNativeDirectAudioChannelCount(YAudioCodec.Aac, 1, 8, sourceAudioTrackCount = 2))
        assertEquals(44_100, resolveNativeDirectAudioSampleRate(44_100, 96_000, sourceAudioTrackCount = 2))
    }

    @Test
    fun `known audio mime types keep their identity`() {
        assertEquals(YAudioCodec.Aac, "audio/mp4a-latm".toYAudioCodec())
        assertEquals(YAudioCodec.Eac3, "audio/eac3".toYAudioCodec())
        assertEquals(YAudioCodec.TrueHd, "audio/true-hd".toYAudioCodec())
        assertEquals(YAudioCodec.DtsHd, "audio/vnd.dts.hd".toYAudioCodec())
    }

    @Test
    fun `dts x is reachable from its mime type`() {
        // DtsX existed in YAudioCodec with nothing mapping onto it, so a DTS:X track could never
        // be identified as one.
        assertEquals(YAudioCodec.DtsX, "audio/vnd.dts.uhd".toYAudioCodec())
    }

    @Test
    fun `codec parameters and casing do not hide a supported codec`() {
        assertEquals(YAudioCodec.DtsX, "audio/vnd.dts.uhd; profile=p2".toYAudioCodec())
        assertEquals("audio/vnd.dts.uhd", " Audio/Vnd.Dts.Uhd; profile=p2 ".normalizedAudioMimeType())
        assertEquals(YAudioCodec.Aac, "Audio/MP4A-LATM".toYAudioCodec())
        assertEquals(YAudioCodec.Eac3, " audio/eac3 ".toYAudioCodec())
    }

    @Test
    fun `missing extractor audio geometry uses server hints before safe defaults`() {
        assertEquals(
            8,
            resolveNativeDirectAudioChannelCount(
                codec = YAudioCodec.TrueHd,
                extractedChannelCount = 0,
                sourceHintChannelCount = 8,
            ),
        )
        assertEquals(
            96_000,
            resolveNativeDirectAudioSampleRate(
                extractedSampleRateHz = 0,
                sourceHintSampleRateHz = 96_000,
            ),
        )
        assertEquals(
            2,
            resolveNativeDirectAudioChannelCount(
                codec = YAudioCodec.Aac,
                extractedChannelCount = 0,
                sourceHintChannelCount = 0,
            ),
        )
        assertEquals(48_000, resolveNativeDirectAudioSampleRate(0, 0))
    }

    @Test
    fun `extractor audio geometry remains authoritative`() {
        assertEquals(
            2,
            resolveNativeDirectAudioChannelCount(
                codec = YAudioCodec.TrueHd,
                extractedChannelCount = 2,
                sourceHintChannelCount = 8,
            ),
        )
        assertEquals(44_100, resolveNativeDirectAudioSampleRate(44_100, 96_000))
    }

    @Test
    fun `unmapped audio stays null so the decoder capability set is not widened`() {
        // AndroidYCapabilityProvider builds the device decoder set through this function. Mapping
        // an unrecognised type to Unknown here would claim support for every codec; only callers
        // describing one concrete track substitute Unknown.
        assertNull("audio/raw".toYAudioCodec())
        assertNull("audio/vorbis".toYAudioCodec())
        assertNull("video/avc".toYAudioCodec())
        assertNull("".toYAudioCodec())
    }
}
