package com.yfuse.core2.hdr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class YHdr10PlusParserTest {
    @Test
    fun parses_per_picture_luminance_and_tone_mapping() {
        val bits = BitWriter()
        bits.write(0, 8) // application version
        bits.write(1, 2) // one processing window
        bits.write(1_000, 27)
        bits.write(0, 1) // no targeted peak grid
        listOf(10_000, 20_000, 30_000).forEach { bits.write(it, 17) }
        bits.write(5_000, 17)
        bits.write(0, 4)
        bits.write(100, 10)
        bits.write(0, 1) // no mastering peak grid
        bits.write(1, 1)
        bits.write(2_048, 12)
        bits.write(1_024, 12)
        bits.write(2, 4)
        bits.write(256, 10)
        bits.write(768, 10)
        bits.write(0, 1)

        val payload = IDENTIFIER + bits.toByteArray()
        val parsed = assertNotNull(YHdr10PlusParser.parse(payload))
        assertEquals(1_000f, parsed.targetedDisplayMaximumNits)
        assertEquals(listOf(1_000f, 2_000f, 3_000f), parsed.maxSclNits)
        assertEquals(500f, parsed.averageMaxRgbNits)
        assertEquals(3_000f, parsed.scenePeakNits)
        assertEquals(2_048 / 4095f, assertNotNull(parsed.kneePointX), 0.0001f)
        assertEquals(listOf(256 / 1023f, 768 / 1023f), parsed.bezierAnchors)
    }

    @Test
    fun rejects_non_hdr10_plus_and_truncated_payloads() {
        assertEquals(null, YHdr10PlusParser.parse(byteArrayOf(1, 2, 3)))
        assertEquals(null, YHdr10PlusParser.parse(IDENTIFIER + byteArrayOf(0)))
    }

    private class BitWriter {
        private val bits = mutableListOf<Int>()

        fun write(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) bits += (value ushr shift) and 1
        }

        fun toByteArray(): ByteArray =
            ByteArray((bits.size + 7) / 8) { byteIndex ->
                var value = 0
                repeat(8) { bit -> value = (value shl 1) or bits.getOrElse(byteIndex * 8 + bit) { 0 } }
                value.toByte()
            }
    }

    private companion object {
        val IDENTIFIER = byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04)
    }
}
