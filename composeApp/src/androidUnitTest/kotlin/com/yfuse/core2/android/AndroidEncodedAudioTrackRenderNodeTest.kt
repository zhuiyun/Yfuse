package com.yfuse.core2.android

import android.media.AudioFormat
import android.os.Build
import com.yfuse.core2.capability.YAudioCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEncodedAudioTrackRenderNodeTest {
    @Test
    fun `maps only Android encoded passthrough codecs`() {
        assertEquals(
            AudioFormat.ENCODING_E_AC3_JOC,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
        assertEquals(AudioFormat.ENCODING_DOLBY_TRUEHD, androidEncodedAudioEncoding(YAudioCodec.TrueHdAtmos))
        assertEquals(AudioFormat.ENCODING_DTS_HD, androidEncodedAudioEncoding(YAudioCodec.DtsHd))
        assertEquals(AudioFormat.ENCODING_DTS_HD, androidEncodedAudioEncoding(YAudioCodec.DtsX))
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Aac))
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Flac))
    }

    @Test
    fun `truehd tries the mat carrier after the truehd encoding from api 30`() {
        assertEquals(
            listOf(AudioFormat.ENCODING_DOLBY_TRUEHD, AudioFormat.ENCODING_DOLBY_MAT),
            androidEncodedAudioEncodingCandidates(YAudioCodec.TrueHdAtmos, Build.VERSION_CODES.R),
        )
        assertEquals(
            listOf(AudioFormat.ENCODING_DOLBY_TRUEHD),
            androidEncodedAudioEncodingCandidates(YAudioCodec.TrueHd, Build.VERSION_CODES.P),
        )
        assertEquals(
            listOf(AudioFormat.ENCODING_E_AC3_JOC),
            androidEncodedAudioEncodingCandidates(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
        assertEquals(emptyList(), androidEncodedAudioEncodingCandidates(YAudioCodec.Aac))
    }

    @Test
    fun `mat carrier counts as truehd passthrough capability`() {
        assertEquals(setOf(YAudioCodec.TrueHd), audioCodecsForEncoding(AudioFormat.ENCODING_DOLBY_MAT))
        assertEquals(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            directPlaybackProbeChannelMask(AudioFormat.ENCODING_DOLBY_MAT),
        )
        assertEquals(
            AudioFormat.CHANNEL_OUT_5POINT1,
            directPlaybackProbeChannelMask(AudioFormat.ENCODING_E_AC3_JOC),
        )
    }

    @Test
    fun `uses standard eac3 carrier for joc before api 28`() {
        assertEquals(
            AudioFormat.ENCODING_E_AC3,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.O_MR1),
        )
        assertEquals(
            AudioFormat.ENCODING_E_AC3_JOC,
            androidEncodedAudioEncoding(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
    }

    @Test
    fun `base carrier encodings do not advertise object audio`() {
        assertEquals(setOf(YAudioCodec.Eac3), audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3))
        assertEquals(setOf(YAudioCodec.TrueHd), audioCodecsForEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD))
        assertEquals(setOf(YAudioCodec.DtsHd), audioCodecsForEncoding(AudioFormat.ENCODING_DTS_HD_MA))
        assertFalse(YAudioCodec.Eac3Joc in audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3))
    }

    @Test
    fun `exact joc encoding preserves positive object audio capability`() {
        val codecs = audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3_JOC)

        assertTrue(YAudioCodec.Eac3 in codecs)
        assertTrue(YAudioCodec.Eac3Joc in codecs)
    }

    @Test
    fun `joc uses base carrier unless the sink exposes exact transport`() {
        assertEquals(
            YAudioCodec.Eac3,
            encodedSinkCodec(YAudioCodec.Eac3Joc, exactDolbyAtmosTransport = false),
        )
        assertEquals(
            YAudioCodec.Eac3Joc,
            encodedSinkCodec(YAudioCodec.Eac3Joc, exactDolbyAtmosTransport = true),
        )
    }
}
