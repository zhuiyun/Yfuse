package com.yfuse.core2.hdr

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class YHdrMetadataTest {
    @Test
    fun `encodes CTA static descriptor in little endian field order`() {
        val metadata =
            YHdrStaticMetadata(
                redX = 1,
                redY = 2,
                greenX = 3,
                greenY = 4,
                blueX = 5,
                blueY = 6,
                whiteX = 7,
                whiteY = 8,
                maxDisplayLuminance = 1_000,
                minDisplayLuminance = 50,
                maxContentLightLevel = 4_000,
                maxFrameAverageLightLevel = 600,
            )

        assertContentEquals(
            byteArrayOf(
                0,
                1,
                0,
                2,
                0,
                3,
                0,
                4,
                0,
                5,
                0,
                6,
                0,
                7,
                0,
                8,
                0,
                0xe8.toByte(),
                0x03,
                50,
                0,
                0xa0.toByte(),
                0x0f,
                0x58,
                0x02,
            ),
            metadata.toCta8613Bytes(),
        )
    }

    @Test
    fun `rejects values outside CTA unsigned short range`() {
        assertFailsWith<IllegalArgumentException> {
            YHdrStaticMetadata(maxContentLightLevel = 65_536)
        }
    }
}
