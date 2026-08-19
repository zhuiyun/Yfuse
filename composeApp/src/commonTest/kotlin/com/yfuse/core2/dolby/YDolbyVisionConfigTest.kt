package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YHdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YDolbyVisionConfigTest {
    @Test
    fun `Profile 8 point 1 parses as HEVC with HDR10 compatible base`() {
        val config =
            YDolbyVisionConfig.parse(
                doviConfig(
                    profile = 8,
                    level = 6,
                    rpu = true,
                    el = false,
                    bl = true,
                    compatibilityId = 1,
                ),
            )

        assertEquals(8, config.profile)
        assertEquals(6, config.level)
        assertTrue(config.rpuPresent)
        assertFalse(config.enhancementLayerPresent)
        assertTrue(config.baseLayerPresent)
        assertEquals(YDolbyVisionCodecFamily.Hevc, config.codecFamily)
        assertEquals(YHdrType.Hdr10, config.compatibleBaseHdr)
    }

    @Test
    fun `Profile 8 point 4 exposes HLG compatible base`() {
        val config =
            YDolbyVisionConfig.parse(
                doviConfig(
                    profile = 8,
                    level = 8,
                    rpu = true,
                    el = false,
                    bl = true,
                    compatibilityId = 4,
                ),
            )

        assertEquals(YHdrType.Hlg, config.compatibleBaseHdr)
    }

    @Test
    fun `Profile 10 is modeled as AV1 and may carry HDR10 compatible base`() {
        val config =
            YDolbyVisionConfig.parse(
                doviConfig(
                    profile = 10,
                    level = 5,
                    rpu = true,
                    el = false,
                    bl = true,
                    compatibilityId = 1,
                ),
            )

        assertEquals(YDolbyVisionCodecFamily.Av1, config.codecFamily)
        assertTrue(config.supportedAndroidProfileFamily)
        assertEquals(YHdrType.Hdr10, config.compatibleBaseHdr)
    }

    @Test
    fun `older four-byte config keeps compatibility unknown`() {
        val full = doviConfig(profile = 5, level = 6, rpu = true, el = false, bl = true)
        val config = YDolbyVisionConfig.parse(full.copyOf(4))

        assertEquals(0, config.baseLayerCompatibilityId)
        assertEquals(0, config.metadataCompression)
        assertNull(config.compatibleBaseHdr)
    }

    @Test
    fun `Profile 7 source EL is not a FEL output claim`() {
        val config =
            YDolbyVisionConfig.parse(
                doviConfig(
                    profile = 7,
                    level = 6,
                    rpu = true,
                    el = true,
                    bl = true,
                ),
            )
        val stream =
            YDolbyVisionStreamEvidence(
                config = config,
                observedNals =
                    YDolbyVisionNalEvidence(
                        rpuCount = 5,
                        enhancementLayerCount = 5,
                    ),
            )

        assertTrue(stream.enhancementLayerPresent)
        assertFalse(stream.canClaimFELComposition)
        assertFalse(YDolbyVisionOutputEvidence(stream).canClaimFELComposition)
        assertFalse(
            YDolbyVisionOutputEvidence(
                stream = stream,
                enhancementLayerComposed = false,
            ).canClaimFELComposition,
        )
        assertTrue(
            YDolbyVisionOutputEvidence(
                stream = stream,
                enhancementLayerComposed = true,
            ).canClaimFELComposition,
        )
    }

    @Test
    fun `truncated Dolby config is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            YDolbyVisionConfig.parse(byteArrayOf(1, 0, 0))
        }
    }

    private fun doviConfig(
        profile: Int,
        level: Int,
        rpu: Boolean,
        el: Boolean,
        bl: Boolean,
        compatibilityId: Int = 0,
        compression: Int = 0,
    ): ByteArray {
        require(profile in 0..127)
        require(level in 0..63)
        require(compatibilityId in 0..15)
        require(compression in 0..3)
        val packed =
            (profile shl 9) or
                (level shl 3) or
                (if (rpu) 0x04 else 0) or
                (if (el) 0x02 else 0) or
                (if (bl) 0x01 else 0)
        return byteArrayOf(
            1,
            0,
            (packed ushr 8).toByte(),
            packed.toByte(),
            ((compatibilityId shl 4) or (compression shl 2)).toByte(),
        )
    }
}
