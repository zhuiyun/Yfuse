package com.yfuse.core2.bitstream

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    fun `an hvcC without parameter sets leaves them to the keyframes instead of failing`() {
        // What one Dolby Vision Profile 5 MKV carried: a valid record with no NAL arrays at all.
        val empty =
            ByteArray(23).apply {
                this[0] = 1
                this[21] = 0xff.toByte()
            }
        assertNull(YCodecConfiguration.hevcParameterSetsAnnexB(empty))
        assertFailsWith<IllegalArgumentException> { YCodecConfiguration.parseHvcC(empty) }

        val vps = byteArrayOf(0x40, 0x01, 0x0c)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xc0.toByte())
        val complete =
            empty.copyOf().apply { this[22] = 3 } + hevcArray(32, vps) + hevcArray(33, sps) + hevcArray(34, pps)
        assertContentEquals(
            YCodecConfiguration.parseHvcC(complete).csd0AnnexB(),
            YCodecConfiguration.hevcParameterSetsAnnexB(complete),
        )
        // Only an SPS is still no usable configuration.
        assertNull(
            YCodecConfiguration.hevcParameterSetsAnnexB(empty.copyOf().apply { this[22] = 1 } + hevcArray(33, sps)),
        )
        // A record that is malformed, rather than merely empty, still fails.
        assertFailsWith<IllegalArgumentException> {
            YCodecConfiguration.hevcParameterSetsAnnexB(empty.copyOf().apply { this[22] = 1 })
        }
    }

    @Test
    fun `record parameter sets report what an empty or partial record carries`() {
        val emptyAvcC = byteArrayOf(1, 100, 0, 31, 0xff.toByte(), 0xe0.toByte(), 0)
        val emptyAvc = YCodecConfiguration.avcRecordParameterSets(emptyAvcC)
        assertEquals(0, emptyAvc.sps.size)
        assertEquals(0, emptyAvc.pps.size)
        assertEquals(false, emptyAvc.complete)
        // parseAvcC still refuses it as a configuration; only the parameter-set view accepts it.
        assertFailsWith<IllegalArgumentException> { YCodecConfiguration.parseAvcC(emptyAvcC) }

        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val partialHvcC =
            ByteArray(23).apply {
                this[0] = 1
                this[21] = 0xff.toByte()
                this[22] = 1
            } + hevcArray(33, sps)
        val partial = YCodecConfiguration.hevcRecordParameterSets(partialHvcC)
        assertContentEquals(sps, partial.sps.single())
        assertEquals(false, partial.complete)
        val pps = byteArrayOf(0x44, 0x01, 0xc0.toByte())
        val completed = YParameterSets(pps = listOf(pps)).orElse(partial)
        assertEquals(true, completed.complete)
        assertContentEquals(sps, completed.sps.single())

        // Malformed, rather than merely empty: the PPS count byte is missing.
        assertFailsWith<IllegalArgumentException> {
            YCodecConfiguration.avcRecordParameterSets(byteArrayOf(1, 100, 0, 31, 0xff.toByte(), 0xe0.toByte()))
        }
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

    @Test
    fun `av1C exposes profile level bit depth and config OBUs`() {
        val configObus = byteArrayOf(0x0a, 0x02, 0x11, 0x22)
        val config =
            YCodecConfiguration.parseAv1C(
                byteArrayOf(0x81.toByte(), 0x4d, 0xcc.toByte(), 0x15) + configObus,
            )

        assertEquals(2, config.sequenceProfile)
        assertEquals(13, config.sequenceLevelIndex)
        assertEquals(1, config.sequenceTier)
        assertEquals(true, config.highBitDepth)
        assertEquals(5, config.initialPresentationDelayMinusOne)
        assertContentEquals(configObus, config.configObus)
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
