package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YCompressedSample
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.subtitle.YSubtitleFormat
import com.yfuse.core2.subtitle.YSubtitlePayload
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        assertEquals(YAudioCodec.DtsX, ffmpegAudioCodec("dca", 61))
        assertEquals(YAudioCodec.DtsX, ffmpegAudioCodec("dca", 62))
        assertEquals(YAudioCodec.Alac, ffmpegAudioCodec("alac", -99))
        assertEquals(YAudioCodec.Mp3, ffmpegAudioCodec("mp3", -99))
        assertEquals(YAudioCodec.Eac3Joc, ffmpegAudioCodec("eac3", 30))
        assertEquals(YAudioCodec.TrueHdAtmos, ffmpegAudioCodec("truehd", 30))
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

    @Test
    fun `FFmpeg subtitle codec names map to stable overlay formats`() {
        assertEquals(YSubtitleFormat.Srt, ffmpegSubtitleFormat("subrip"))
        assertEquals(YSubtitleFormat.Ass, ffmpegSubtitleFormat("ass"))
        assertEquals(YSubtitleFormat.Pgs, ffmpegSubtitleFormat("hdmv_pgs_subtitle"))
        assertEquals(YSubtitleFormat.Tx3g, ffmpegSubtitleFormat("mov_text"))
        assertEquals(YSubtitleFormat.Unknown, ffmpegSubtitleFormat("unknown_subtitle"))
    }

    @Test
    fun `MediaExtractor subtitle MIME maps to the same overlay formats`() {
        assertEquals(YSubtitleFormat.Tx3g, mediaSubtitleFormat("application/x-quicktime-tx3g"))
        assertEquals(YSubtitleFormat.Srt, mediaSubtitleFormat("application/x-subrip"))
        assertNull(mediaSubtitleFormat("video/hevc"))
    }

    @Test
    fun `native FFmpeg failures keep network and authorization out of device learning`() {
        assertEquals(YPlaybackFailureCategory.Authorization, ffmpegFailureCategory(FFMPEG_FAILURE_AUTHORIZATION))
        assertEquals(YPlaybackFailureCategory.Network, ffmpegFailureCategory(FFMPEG_FAILURE_NETWORK))
        assertEquals(YPlaybackFailureCategory.Container, ffmpegFailureCategory(FFMPEG_FAILURE_CONTAINER))
    }

    @Test
    fun `native bitmap subtitle payload preserves authored canvas and ARGB pixels`() {
        val payload =
            ByteBuffer
                .allocate((7 + 7 + 2) * Int.SIZE_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    putInt(0x42555359)
                    putInt(1)
                    putInt(1920)
                    putInt(1080)
                    putInt(100)
                    putInt(1_600)
                    putInt(1)
                    putInt(100)
                    putInt(900)
                    putInt(2)
                    putInt(1)
                    putInt(0)
                    putInt(2)
                    putInt(0)
                    putInt(0xff112233.toInt())
                    putInt(0x80112233.toInt())
                }.array()

        val cue =
            payload
                .toBitmapSubtitleCues(
                    YCompressedSample(
                        trackId = YTrackId(3),
                        data = byteArrayOf(1),
                        presentationTimeUs = 2_000_000L,
                    ),
                ).single()
        val bitmap = cue.payload as YSubtitlePayload.BitmapArgb
        assertEquals(2_100_000L, cue.startUs)
        assertEquals(3_600_000L, cue.endUs)
        assertEquals(100, bitmap.x)
        assertEquals(900, bitmap.y)
        assertEquals(1920, bitmap.canvasWidth)
        assertEquals(listOf(0xff112233.toInt(), 0x80112233.toInt()), bitmap.pixels.toList())
    }
}
