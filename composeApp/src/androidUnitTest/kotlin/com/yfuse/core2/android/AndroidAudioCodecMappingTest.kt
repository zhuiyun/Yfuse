package com.yfuse.core2.android

import com.yfuse.core2.capability.YAudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAudioCodecMappingTest {
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
        assertEquals(YAudioCodec.Aac, "Audio/MP4A-LATM".toYAudioCodec())
        assertEquals(YAudioCodec.Eac3, " audio/eac3 ".toYAudioCodec())
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
