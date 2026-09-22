package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class YDolbyVisionBitstreamTest {
    @Test
    fun `compatible HEVC base removes RPU and enhancement layer NALs`() {
        val source =
            annexB(
                hevcNal(32, 0x11),
                hevcNal(1, 0x22),
                hevcNal(62, 0x33),
                hevcNal(63, 0x44),
            )

        val base = dolbyVisionHevcBaseLayerSample(source, YSamplePacking.AnnexB)
        val types = YBitstream.scan(base, YNalCodec.H265, YSamplePacking.AnnexB).map { it.type }

        assertEquals(listOf(32, 1), types)
        assertFalse(YBitstream.containsDolbyVisionRpu(base, YSamplePacking.AnnexB))
        assertFalse(YBitstream.containsDolbyVisionEnhancementLayer(base, YSamplePacking.AnnexB))
    }

    private fun annexB(vararg units: ByteArray): ByteArray =
        units.fold(ByteArray(0)) { output, unit ->
            output + byteArrayOf(0, 0, 0, 1) + unit
        }

    private fun hevcNal(
        type: Int,
        vararg payload: Int,
    ): ByteArray = byteArrayOf((type shl 1).toByte(), 0x01) + ByteArray(payload.size) { payload[it].toByte() }
}
