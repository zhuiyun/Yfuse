package com.yfuse.core2.bitstream

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YBitstreamTest {
    @Test
    fun `HEVC AnnexB scan exposes parameter sets and Dolby evidence`() {
        val source =
            annexB(
                hevcNal(32, 0x11),
                hevcNal(33, 0x22),
                hevcNal(34, 0x33),
                hevcNal(62, 0x44),
                hevcNal(63, 0x55),
            )

        val types = YBitstream.scan(source, YNalCodec.H265, YSamplePacking.AnnexB).map { it.type }
        val parameterSets = YBitstream.parameterSets(source, YNalCodec.H265, YSamplePacking.AnnexB)
        val dolby = YBitstream.dolbyVisionEvidence(source, YSamplePacking.AnnexB)

        assertEquals(listOf(32, 33, 34, 62, 63), types)
        assertEquals(1, parameterSets.vps.size)
        assertEquals(1, parameterSets.sps.size)
        assertEquals(1, parameterSets.pps.size)
        assertTrue(dolby.rpuPresent)
        assertTrue(dolby.enhancementLayerPresent)
        assertEquals(1, dolby.rpuCount)
        assertEquals(1, dolby.enhancementLayerCount)
    }

    @Test
    fun `AnnexB and four-byte length-prefix normalization round trips encoded units`() {
        val source =
            annexB(
                hevcNal(32, 0x01, 0x02),
                hevcNal(1, 0x03, 0x04, 0x05),
                hevcNal(62, 0x06),
            )
        val lengthPrefixed =
            YBitstream.normalize(
                data = source,
                codec = YNalCodec.H265,
                from = YSamplePacking.AnnexB,
                to = YSamplePacking.LengthPrefixed(4),
            )
        val restored =
            YBitstream.normalize(
                data = lengthPrefixed,
                codec = YNalCodec.H265,
                from = YSamplePacking.LengthPrefixed(4),
                to = YSamplePacking.AnnexB,
            )

        assertContentEquals(source, restored)
        assertTrue(YBitstream.containsDolbyVisionRpu(lengthPrefixed, YSamplePacking.LengthPrefixed(4)))
        assertFalse(
            YBitstream.containsDolbyVisionEnhancementLayer(
                lengthPrefixed,
                YSamplePacking.LengthPrefixed(4),
            ),
        )
    }

    @Test
    fun `long AnnexB zero run belongs to the delimiter not the previous NAL`() {
        val first = hevcNal(1, 0x11, 0x22)
        val second = hevcNal(62, 0x33)
        val source =
            byteArrayOf(0, 0, 0, 1) +
                first +
                byteArrayOf(0, 0, 0, 0, 1) +
                second

        val spans = YBitstream.scan(source, YNalCodec.H265, YSamplePacking.AnnexB)

        assertEquals(2, spans.size)
        assertEquals(first.size, spans[0].length)
        assertEquals(1, spans[0].type)
        assertEquals(62, spans[1].type)
    }

    @Test
    fun `length-prefixed parser rejects truncated or oversized access units`() {
        assertFailsWith<IllegalArgumentException> {
            YBitstream.scan(
                data = byteArrayOf(0, 0, 0),
                codec = YNalCodec.H265,
                packing = YSamplePacking.LengthPrefixed(4),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            YBitstream.scan(
                data = byteArrayOf(0, 0, 0, 5, 0x40, 0x01),
                codec = YNalCodec.H265,
                packing = YSamplePacking.LengthPrefixed(4),
            )
        }
    }

    @Test
    fun `H264 parameter-set scanning stays independent from HEVC type rules`() {
        val source =
            annexB(
                h264Nal(7, 0x64, 0x00),
                h264Nal(8, 0x01),
                h264Nal(5, 0x22),
            )
        val parameterSets = YBitstream.parameterSets(source, YNalCodec.H264, YSamplePacking.AnnexB)
        val types = YBitstream.scan(source, YNalCodec.H264, YSamplePacking.AnnexB).map { it.type }

        assertEquals(listOf(7, 8, 5), types)
        assertEquals(1, parameterSets.sps.size)
        assertEquals(1, parameterSets.pps.size)
        assertTrue(parameterSets.vps.isEmpty())
    }

    private fun annexB(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { output, unit ->
            output + byteArrayOf(0, 0, 0, 1) + unit
        }

    private fun hevcNal(
        type: Int,
        vararg payload: Int,
    ): ByteArray {
        require(type in 0..63)
        return byteArrayOf((type shl 1).toByte(), 0x01) + payload.map(Int::toByte).toByteArray()
    }

    private fun h264Nal(
        type: Int,
        vararg payload: Int,
    ): ByteArray {
        require(type in 0..31)
        return byteArrayOf(type.toByte()) + payload.map(Int::toByte).toByteArray()
    }
}
