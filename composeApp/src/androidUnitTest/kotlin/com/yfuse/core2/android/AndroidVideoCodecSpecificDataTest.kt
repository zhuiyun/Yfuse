package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackFailureStage
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YCodecPrivateData
import com.yfuse.core2.demux.YVideoTrackFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidVideoCodecSpecificDataTest {
    private val vps = byteArrayOf(0x40, 0x01, 0x0c)
    private val sps = byteArrayOf(0x42, 0x01, 0x01)
    private val pps = byteArrayOf(0x44, 0x01, 0xc0.toByte())
    private val idrSlice = byteArrayOf(0x26, 0x01, 0x55, 0x66)
    private val avcSps = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
    private val avcPps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
    private val avcIdr = byteArrayOf(0x65, 0x11, 0x22)

    @Test
    fun `an hvcC without parameter sets takes csd-0 from the first keyframe`() {
        // The Dolby Vision Profile 5 MKV behind incident B: a valid record with no NAL arrays at all.
        val track = hevcTrack(hvcC())
        val keyframe = lengthPrefixed(vps, sps, pps, idrSlice)

        assertTrue(videoParameterSetsMissing(track))
        val sets = assertNotNull(inBandParameterSets(keyframe, track))
        val data = assertNotNull(h26xCodecSpecificData(track, sets))

        assertContentEquals(annexB(vps, sps, pps), data.csd0)
        assertNull(data.csd1)
        assertTrue(data.parameterSetsMissing)
    }

    @Test
    fun `a partial hvcC is completed from the keyframe`() {
        val track = hevcTrack(hvcC(32 to vps, 33 to sps))
        val keyframe = lengthPrefixed(pps, idrSlice)

        val data = assertNotNull(h26xCodecSpecificData(track, inBandParameterSets(keyframe, track)))

        assertContentEquals(annexB(vps, sps, pps), data.csd0)
    }

    @Test
    fun `without a keyframe the decoder still starts csd-less`() {
        val data = assertNotNull(h26xCodecSpecificData(hevcTrack(hvcC(33 to sps))))

        assertNull(data.csd0)
        assertTrue(data.parameterSetsMissing)
    }

    @Test
    fun `a complete hvcC keeps its own configuration`() {
        val track = hevcTrack(hvcC(32 to vps, 33 to sps, 34 to pps))
        val otherSps = byteArrayOf(0x42, 0x01, 0x7f)

        val data =
            assertNotNull(
                h26xCodecSpecificData(track, inBandParameterSets(lengthPrefixed(otherSps, pps, idrSlice), track)),
            )

        assertFalse(videoParameterSetsMissing(track))
        assertFalse(data.parameterSetsMissing)
        assertContentEquals(annexB(vps, sps, pps), data.csd0)
    }

    @Test
    fun `an avcC without SPS and PPS takes both from the first keyframe`() {
        // Seven bytes: header, zero SPS, zero PPS. parseAvcC rejects it as a configuration.
        val empty = byteArrayOf(1, 100, 0, 31, 0xff.toByte(), 0xe0.toByte(), 0)
        val track = avcTrack(empty)
        val keyframe = lengthPrefixed(avcSps, avcPps, avcIdr)

        assertTrue(videoParameterSetsMissing(track))
        val data = assertNotNull(h26xCodecSpecificData(track, inBandParameterSets(keyframe, track)))

        assertContentEquals(annexB(avcSps), data.csd0)
        assertContentEquals(annexB(avcPps), data.csd1)
    }

    @Test
    fun `a partial avcC takes only the missing PPS from the keyframe`() {
        val spsOnly =
            byteArrayOf(1, 100, 0, 31, 0xff.toByte(), 0xe1.toByte(), 0, avcSps.size.toByte()) + avcSps + byteArrayOf(0)
        val track = avcTrack(spsOnly)

        val data =
            assertNotNull(h26xCodecSpecificData(track, inBandParameterSets(lengthPrefixed(avcPps, avcIdr), track)))

        assertContentEquals(annexB(avcSps), data.csd0)
        assertContentEquals(annexB(avcPps), data.csd1)
    }

    @Test
    fun `a malformed record is a deterministic bitstream failure and no decoder strike`() {
        // One NAL array declared, none present.
        val malformed = hvcC().copyOf().apply { this[22] = 1 }

        val failure =
            assertFailsWith<YPlaybackException> {
                yVideoFormatStage("Enhanced video codec configuration") {
                    videoParameterSetsMissing(hevcTrack(malformed))
                }
            }

        assertEquals(YPlaybackFailureStage.Bitstream, failure.stage)
        assertEquals(YPlaybackFailureCategory.Container, failure.category)
        assertTrue(failure.deterministic)
        assertIs<IllegalArgumentException>(failure.cause)
        assertFalse(failure.isRuntimeDecoderRejection())
    }

    @Test
    fun `other codecs are left to the existing private-data path`() {
        val av1 = YVideoTrackFormat(codec = YVideoCodec.Av1, mimeType = "video/av01")

        assertNull(h26xCodecSpecificData(av1))
        assertFalse(videoParameterSetsMissing(av1))
    }

    private fun hevcTrack(record: ByteArray) =
        YVideoTrackFormat(
            codec = YVideoCodec.H265,
            mimeType = "video/hevc",
            samplePacking = YSamplePacking.LengthPrefixed(4),
            codecPrivateData = YCodecPrivateData(listOf(record)),
        )

    private fun avcTrack(record: ByteArray) =
        YVideoTrackFormat(
            codec = YVideoCodec.H264,
            mimeType = "video/avc",
            samplePacking = YSamplePacking.LengthPrefixed(4),
            codecPrivateData = YCodecPrivateData(listOf(record)),
        )

    private fun hvcC(vararg arrays: Pair<Int, ByteArray>): ByteArray {
        val header =
            ByteArray(23).apply {
                this[0] = 1
                this[21] = 0xff.toByte()
                this[22] = arrays.size.toByte()
            }
        return arrays.fold(header) { record, (type, nal) ->
            record + byteArrayOf(type.toByte(), 0, 1, 0, nal.size.toByte()) + nal
        }
    }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { sample, nal -> sample + byteArrayOf(0, 0, 0, nal.size.toByte()) + nal }

    private fun annexB(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(0)) { csd, nal -> csd + byteArrayOf(0, 0, 0, 1) + nal }
}
