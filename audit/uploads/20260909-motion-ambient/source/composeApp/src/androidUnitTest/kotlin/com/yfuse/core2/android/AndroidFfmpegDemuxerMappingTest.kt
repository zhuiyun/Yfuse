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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidFfmpegDemuxerMappingTest {
    @Test
    fun `FFmpeg video codec names map without involving a decoder`() {
        assertEquals(YVideoCodec.H264, ffmpegVideoCodec("h264"))
        assertEquals(YVideoCodec.H265, ffmpegVideoCodec("hevc"))
        assertEquals(YVideoCodec.Av1, ffmpegVideoCodec("av1"))
        assertEquals(YVideoCodec.Vp9, ffmpegVideoCodec("vp9"))
        assertEquals(YVideoCodec.Vc1, ffmpegVideoCodec("vc1"))
        assertEquals(YVideoCodec.Vc1, ffmpegVideoCodec("wmv3"))
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
    fun `baseline AAC and AC3 codecs remain native-decodable identities`() {
        assertEquals(YAudioCodec.Aac, ffmpegAudioCodec("aac", -99))
        assertEquals(YAudioCodec.Ac3, ffmpegAudioCodec("ac3", -99))
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
        assertNull(ffmpegFailureDetail(FFMPEG_FAILURE_CONTAINER))
    }

    @Test
    fun `packed native open failures expose the stage and AVERROR without changing the class`() {
        // AVERROR_INVALIDDATA is FFERRTAG('I','N','D','A'): little-endian tag bytes.
        val invalidData = 0x41444E49L
        val packed = -((FFMPEG_OPEN_STAGE_OPEN_INPUT.toLong() shl 40) or (4L shl 32) or invalidData)
        assertEquals(YPlaybackFailureCategory.Container, ffmpegFailureCategory(packed))
        assertEquals("stage=open_input error=tag:INDA", ffmpegFailureDetail(packed))

        // AVERROR_HTTP_NOT_FOUND is FFERRTAG(0xF8,'4','0','4') and classifies as Network.
        val notFound = 0x343034F8L
        val network = -((FFMPEG_OPEN_STAGE_STREAM_INFO.toLong() shl 40) or (3L shl 32) or notFound)
        assertEquals(YPlaybackFailureCategory.Network, ffmpegFailureCategory(network))
        assertEquals("stage=find_stream_info error=tag:!404", ffmpegFailureDetail(network))

        val timedOut = -((FFMPEG_OPEN_STAGE_DISC.toLong() shl 40) or (3L shl 32) or 110L)
        assertEquals("stage=disc_open error=errno:110", ffmpegFailureDetail(timedOut))
    }

    @Test
    fun `native open statuses in an unrecognised layout stay Unknown instead of penalising the container`() {
        // A packed status whose class byte is not one this bridge knows: decodable only against
        // the native artifact that produced it, so the raw value stays in the detail.
        val unknownClass = -((FFMPEG_OPEN_STAGE_OPEN_INPUT.toLong() shl 40) or (9L shl 32) or 0x41444E49L)
        assertEquals(YPlaybackFailureCategory.Unknown, ffmpegFailureCategory(unknownClass))
        assertEquals(
            "stage=open_input class=9 raw=$unknownClass error=tag:INDA",
            ffmpegFailureDetail(unknownClass),
        )

        // A bare status outside the -2/-3/-4 classes is equally undecodable.
        assertEquals(YPlaybackFailureCategory.Unknown, ffmpegFailureCategory(-1L))
        assertNull(ffmpegFailureDetail(-1L))
    }

    @Test
    fun `tagged heap pointers returned as session handles are not mistaken for open failures`() {
        // Recorded by a 1.0.28 OPPO PLG110 on Android 16: libycore_demux.so returned the session
        // pointer as the handle, and Android's 0xb4 top-byte pointer tag makes it negative. The
        // bridge decoded it as "class=145 raw=-5476376674047646560 error=code:3906493280" and
        // reported a playable Dolby Vision MKV as an unknown open failure.
        assertFalse(isFfmpegOpenFailure(-5_476_376_674_047_646_560L))
        // The first session of the same bundle landed on a pointer whose bits happened to read as
        // the Container class and was reported as a corrupt file.
        assertFalse(isFfmpegOpenFailure(-5_476_376_067_973_100_208L))
        // Untagged pointers and registry ids are positive and were never at risk.
        assertFalse(isFfmpegOpenFailure(0x7F00_1234_5678L))
        assertFalse(isFfmpegOpenFailure(1L))

        // Every status the native artifact can actually produce still classifies as a failure.
        assertTrue(isFfmpegOpenFailure(-1L))
        assertTrue(isFfmpegOpenFailure(FFMPEG_FAILURE_AUTHORIZATION))
        assertTrue(isFfmpegOpenFailure(FFMPEG_FAILURE_NETWORK))
        assertTrue(isFfmpegOpenFailure(FFMPEG_FAILURE_CONTAINER))
        val packed = -((FFMPEG_OPEN_STAGE_STREAM_INFO.toLong() shl 40) or (3L shl 32) or 0x343034F8L)
        assertTrue(isFfmpegOpenFailure(packed))
        assertTrue(isFfmpegOpenFailure(-(FFMPEG_OPEN_STATUS_LIMIT - 1L)))
        assertFalse(isFfmpegOpenFailure(-FFMPEG_OPEN_STATUS_LIMIT - 1L))
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
