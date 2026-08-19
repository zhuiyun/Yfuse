package com.yfuse.core2.android

import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YVideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AndroidFfmpegDemuxerMappingTest {
    @Test
    fun `FFmpeg video codec names map without involving a decoder`() {
        assertEquals(YVideoCodec.H264, ffmpegVideoCodec("h264"))
        assertEquals(YVideoCodec.H265, ffmpegVideoCodec("hevc"))
        assertEquals(YVideoCodec.Av1, ffmpegVideoCodec("av1"))
        assertEquals(YVideoCodec.ProRes, ffmpegVideoCodec("prores"))
        assertEquals(YVideoCodec.Unknown, ffmpegVideoCodec("unknown_codec"))
    }

    @Test
    fun `DTS core and DTS-HD profiles stay distinct`() {
        assertEquals(YAudioCodec.Dts, ffmpegAudioCodec("dca", 20))
        assertEquals(YAudioCodec.DtsHd, ffmpegAudioCodec("dca", 50))
        assertEquals(YAudioCodec.DtsHd, ffmpegAudioCodec("dca", 60))
        assertEquals(YAudioCodec.DtsHd, ffmpegAudioCodec("dca", 61))
        assertEquals(YAudioCodec.DtsHd, ffmpegAudioCodec("dca", 62))
    }

    @Test
    fun `PCM 24-bit family stays PCM instead of unknown audio`() {
        assertEquals(YAudioCodec.Pcm, ffmpegAudioCodec("pcm_s24le", -99))
    }

    @Test
    fun `NAL packing metadata preserves AnnexB and length prefix width`() {
        assertEquals(YSamplePacking.AnnexB, ffmpegPacking(FFMPEG_PACKING_ANNEX_B, 0))
        val lengthPrefixed =
            assertIs<YSamplePacking.LengthPrefixed>(
                ffmpegPacking(FFMPEG_PACKING_LENGTH_PREFIXED, 4),
            )
        assertEquals(4, lengthPrefixed.lengthBytes)
        assertNull(ffmpegPacking(FFMPEG_PACKING_LENGTH_PREFIXED, 0))
    }

    @Test
    fun `FFmpeg container aliases normalize to Core2 identities`() {
        assertEquals(YContainer.WebM, ffmpegContainer("webm"))
        assertEquals(YContainer.Matroska, ffmpegContainer("matroska,webm"))
        assertEquals(YContainer.MpegTs, ffmpegContainer("mpegts"))
        assertEquals(YContainer.Mp4, ffmpegContainer("mov,mp4,m4a,3gp,3g2,mj2"))
    }
}
