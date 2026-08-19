package com.yfuse.core2.bitstream

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YCodecConfigurationTest {
    @Test
    fun `avcC exposes length width and separate SPS PPS CSD`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
        val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c, 0x80.toByte())
        val config =
            YCodecConfiguration.parseAvcC(
                byteArrayOf(
                    1,
                    100,
                    0,
                    31,
                    0xff.toByte(),
                    0xe1.toByte(),
                    0,
                    sps.size.toByte(),
                ) + sps +
                    byteArrayOf(1, 0, pps.size.toByte()) + pps,
            )

        assertEquals(4, config.lengthBytes)
        assertEquals(1, config.sps.size)
        assertEquals(1, config.pps.size)
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + sps, config.csd0AnnexB())
        assertContentEquals(byteArrayOf(0, 0, 0, 1) + pps, config.csd1AnnexB())
    }

    @Test
    fun `hvcC collects only VPS SPS PPS and preserves length field width`() {
        val vps = byteArrayOf(0x40, 0x01, 0x11)
        val sps = byteArrayOf(0x42, 0x01, 0x22)
        val pps = byteArrayOf(0x44, 0x01, 0x33)
        val header =
            ByteArray(23).apply {
                this[0] = 1
                this[21] = 0xff.toByte()
                this[22] = 3
            }
        val record =
            header +
                hevcArray(32, vps) +
                hevcArray(33, sps) +
                hevcArray(34, pps)

        val config = YCodecConfiguration.parseHvcC(record)

        assertEquals(4, config.lengthBytes)
        assertContentEquals(vps, config.vps.single())
        assertContentEquals(sps, config.sps.single())
        assertContentEquals(pps, config.pps.single())
        assertContentEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            config.csd0AnnexB(),
        )
    }

    @Test
    fun `truncated configuration records fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            YCodecConfiguration.parseAvcC(byteArrayOf(1, 100, 0, 31, 0xff.toByte(), 0xe1.toByte(), 0, 5, 0x67))
        }
        assertFailsWith<IllegalArgumentException> {
            YCodecConfiguration.parseHvcC(
                ByteArray(23).apply {
                    this[0] = 1
                    this[22] = 1
                },
            )
        }
    }

    private fun hevcArray(
        type: Int,
        nal: ByteArray,
    ): ByteArray =
        byteArrayOf(
            type.toByte(),
            0,
            1,
            ((nal.size ushr 8) and 0xff).toByte(),
            (nal.size and 0xff).toByte(),
        ) + nal
}
