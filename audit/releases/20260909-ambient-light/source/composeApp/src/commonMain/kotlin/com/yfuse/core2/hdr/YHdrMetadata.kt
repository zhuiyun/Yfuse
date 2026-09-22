package com.yfuse.core2.hdr

/**
 * Container-neutral SMPTE ST 2086 and CTA-861.3 static HDR metadata.
 *
 * Chromaticity values use CTA units of 0.00002, minimum luminance uses 0.0001
 * cd/m2, and the remaining luminance values use whole cd/m2. Keeping the
 * integer wire units avoids rounding the metadata more than once.
 */
data class YHdrStaticMetadata(
    val redX: Int = 0,
    val redY: Int = 0,
    val greenX: Int = 0,
    val greenY: Int = 0,
    val blueX: Int = 0,
    val blueY: Int = 0,
    val whiteX: Int = 0,
    val whiteY: Int = 0,
    val maxDisplayLuminance: Int = 0,
    val minDisplayLuminance: Int = 0,
    val maxContentLightLevel: Int = 0,
    val maxFrameAverageLightLevel: Int = 0,
) {
    init {
        values().forEach { require(it in 0..U16_MAX) { "HDR static metadata value is outside uint16" } }
    }

    /** Raw CTA-861.3 Static Metadata Descriptor, including descriptor id 0. */
    fun toCta8613Bytes(): ByteArray {
        val output = ByteArray(CTA861_3_DESCRIPTOR_BYTES)
        values().forEachIndexed { index, value ->
            val offset = 1 + index * 2
            output[offset] = (value and 0xff).toByte()
            output[offset + 1] = (value ushr 8).toByte()
        }
        return output
    }

    private fun values(): List<Int> =
        listOf(
            redX,
            redY,
            greenX,
            greenY,
            blueX,
            blueY,
            whiteX,
            whiteY,
            maxDisplayLuminance,
            minDisplayLuminance,
            maxContentLightLevel,
            maxFrameAverageLightLevel,
        )
}

private const val U16_MAX = 0xffff
private const val CTA861_3_DESCRIPTOR_BYTES = 25
