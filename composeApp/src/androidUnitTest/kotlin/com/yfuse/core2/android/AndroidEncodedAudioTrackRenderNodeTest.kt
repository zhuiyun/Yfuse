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
        assertEquals(
            AudioFormat.ENCODING_AC4,
            androidEncodedAudioEncoding(YAudioCodec.Ac4, Build.VERSION_CODES.Q),
        )
        assertEquals(AudioFormat.ENCODING_DOLBY_TRUEHD, androidEncodedAudioEncoding(YAudioCodec.TrueHdAtmos))
        assertEquals(AudioFormat.ENCODING_DTS_HD, androidEncodedAudioEncoding(YAudioCodec.DtsHd))
        assertEquals(
            AudioFormat.ENCODING_DTS_HD,
            androidEncodedAudioEncoding(YAudioCodec.DtsX, Build.VERSION_CODES.TIRAMISU),
        )
        assertEquals(
            AudioFormat.ENCODING_DTS_UHD_P1,
            androidEncodedAudioEncoding(YAudioCodec.DtsX, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Aac))
        assertNull(androidEncodedAudioEncoding(YAudioCodec.Flac))
    }

    @Test
    fun `truehd uses only framed truehd encoding while dts x tries uhd profiles on api 34`() {
        assertEquals(
            listOf(AudioFormat.ENCODING_DOLBY_TRUEHD),
            androidEncodedAudioEncodingCandidates(YAudioCodec.TrueHdAtmos, Build.VERSION_CODES.R),
        )
        assertEquals(
            listOf(AudioFormat.ENCODING_DOLBY_TRUEHD),
            androidEncodedAudioEncodingCandidates(YAudioCodec.TrueHd, Build.VERSION_CODES.P),
        )
        assertEquals(
            listOf(
                AudioFormat.ENCODING_DTS_UHD_P2,
                AudioFormat.ENCODING_DTS_UHD_P1,
                AudioFormat.ENCODING_DTS_HD,
            ),
            androidEncodedAudioEncodingCandidates(YAudioCodec.DtsX, Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
        )
        assertEquals(
            listOf(AudioFormat.ENCODING_E_AC3_JOC),
            androidEncodedAudioEncodingCandidates(YAudioCodec.Eac3Joc, Build.VERSION_CODES.P),
        )
        assertEquals(emptyList(), androidEncodedAudioEncodingCandidates(YAudioCodec.Aac))
    }

    @Test
    fun `mat carrier is not claimed for raw unframed truehd packets`() {
        assertEquals(emptySet(), audioCodecsForEncoding(AudioFormat.ENCODING_DOLBY_MAT))
        assertEquals(
            AudioFormat.CHANNEL_OUT_7POINT1_SURROUND,
            directPlaybackProbeChannelMask(AudioFormat.ENCODING_DOLBY_TRUEHD),
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
    fun `carrier encodings distinguish dedicated object carriers from shared truehd transport`() {
        assertEquals(setOf(YAudioCodec.Eac3), audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3))
        assertEquals(
            setOf(YAudioCodec.TrueHd, YAudioCodec.TrueHdAtmos),
            audioCodecsForEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD),
        )
        assertEquals(setOf(YAudioCodec.DtsHd), audioCodecsForEncoding(AudioFormat.ENCODING_DTS_HD_MA))
        assertEquals(
            setOf(YAudioCodec.DtsHd, YAudioCodec.DtsX),
            audioCodecsForEncoding(AudioFormat.ENCODING_DTS_UHD_P1),
        )
        assertFalse(YAudioCodec.Eac3Joc in audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3))
    }

    @Test
    fun `exact joc encoding preserves positive object audio capability`() {
        val codecs = audioCodecsForEncoding(AudioFormat.ENCODING_E_AC3_JOC)

        assertTrue(YAudioCodec.Eac3 in codecs)
        assertTrue(YAudioCodec.Eac3Joc in codecs)
    }

    @Test
    fun `atmos codecs use base carrier unless the sink exposes exact transport`() {
        assertEquals(
            YAudioCodec.Eac3,
            encodedSinkCodec(YAudioCodec.Eac3Joc, exactDolbyAtmosTransport = false),
        )
        assertEquals(
            YAudioCodec.Eac3Joc,
            encodedSinkCodec(YAudioCodec.Eac3Joc, exactDolbyAtmosTransport = true),
        )
        assertEquals(
            YAudioCodec.TrueHd,
            encodedSinkCodec(YAudioCodec.TrueHdAtmos, exactDolbyAtmosTransport = false),
        )
        assertEquals(
            YAudioCodec.TrueHdAtmos,
            encodedSinkCodec(YAudioCodec.TrueHdAtmos, exactDolbyAtmosTransport = true),
        )
    }

    @Test
    fun `truehd major sync word is detected at bytes four through seven`() {
        val majorSync =
            byteArrayOf(
                0x00,
                0x40,
                0x00,
                0x00,
                0xF8.toByte(),
                0x72,
                0x6F,
                0xBA.toByte(),
                0x62,
                0x00,
            )
        val nonSync = byteArrayOf(0x00, 0x20, 0x01, 0x02, 0x11, 0x22, 0x33, 0x44)

        assertTrue(isTrueHdMajorSyncPacket(java.nio.ByteBuffer.wrap(majorSync)))
        assertFalse(isTrueHdMajorSyncPacket(java.nio.ByteBuffer.wrap(nonSync)))
        assertFalse(isTrueHdMajorSyncPacket(java.nio.ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0x72, 0x6F))))
    }
}
