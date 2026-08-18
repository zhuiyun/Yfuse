package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YHdrType

enum class YDolbyVisionCodecFamily {
    Hevc,
    Avc,
    Av1,
    Unknown,
}

data class YDolbyVisionConfig(
    val versionMajor: Int,
    val versionMinor: Int,
    val profile: Int,
    val level: Int,
    val rpuPresent: Boolean,
    val enhancementLayerPresent: Boolean,
    val baseLayerPresent: Boolean,
    val baseLayerCompatibilityId: Int,
    val metadataCompression: Int,
) {
    val codecFamily: YDolbyVisionCodecFamily
        get() =
            when (profile) {
                4, 5, 7, 8 -> YDolbyVisionCodecFamily.Hevc
                9 -> YDolbyVisionCodecFamily.Avc
                10 -> YDolbyVisionCodecFamily.Av1
                else -> YDolbyVisionCodecFamily.Unknown
            }

    val supportedAndroidProfileFamily: Boolean
        get() = profile in setOf(4, 5, 7, 8, 9, 10)

    /**
     * Cross-compatible base-layer family that can be used only when the base layer is actually
     * present. Profile 8/10 subprofiles use the BL compatibility id as the suffix (8.1/8.4,
     * 10.1/10.4). P7 fallback is intentionally not inferred here; disc routing validates it from
     * the actual base stream and output evidence.
     */
    val compatibleBaseHdr: YHdrType?
        get() {
            if (!baseLayerPresent || profile !in setOf(8, 10)) return null
            return when (baseLayerCompatibilityId) {
                1 -> YHdrType.Hdr10
                2 -> YHdrType.Sdr
                4 -> YHdrType.Hlg
                else -> null
            }
        }

    companion object {
        /**
         * Parses the payload of an ISOBMFF `dvcC` / `dvvC` / `dvwC` record or an equivalent
         * Matroska block-additional mapping. The four-byte core record is mandatory; the fifth byte
         * carrying compatibility/compression is optional for older records.
         */
        fun parse(data: ByteArray): YDolbyVisionConfig {
            require(data.size >= MIN_DOVI_CONFIG_BYTES) {
                "Dolby Vision configuration requires at least $MIN_DOVI_CONFIG_BYTES bytes"
            }
            val versionMajor = data[0].toInt() and 0xff
            val versionMinor = data[1].toInt() and 0xff
            val packed = ((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff)
            val fifth = data.getOrNull(4)?.toInt()?.and(0xff)
            return YDolbyVisionConfig(
                versionMajor = versionMajor,
                versionMinor = versionMinor,
                profile = (packed ushr 9) and 0x7f,
                level = (packed ushr 3) and 0x3f,
                rpuPresent = packed and 0x04 != 0,
                enhancementLayerPresent = packed and 0x02 != 0,
                baseLayerPresent = packed and 0x01 != 0,
                baseLayerCompatibilityId = fifth?.ushr(4)?.and(0x0f) ?: 0,
                metadataCompression = fifth?.ushr(2)?.and(0x03) ?: 0,
            )
        }
    }
}

data class YDolbyVisionStreamEvidence(
    val config: YDolbyVisionConfig,
    val observedNals: YDolbyVisionNalEvidence = YDolbyVisionNalEvidence(0, 0),
) {
    val rpuPresent: Boolean get() = config.rpuPresent || observedNals.rpuPresent
    val enhancementLayerPresent: Boolean
        get() = config.enhancementLayerPresent || observedNals.enhancementLayerPresent
    val baseLayerPresent: Boolean get() = config.baseLayerPresent

    /** Source evidence alone can never prove that a platform decoder composed a Profile-7 FEL. */
    val canClaimFELComposition: Boolean get() = false
}

data class YDolbyVisionOutputEvidence(
    val stream: YDolbyVisionStreamEvidence,
    /** Independent decoder/display trace proving that the enhancement layer affected output. */
    val enhancementLayerComposed: Boolean? = null,
) {
    val canClaimFELComposition: Boolean
        get() =
            stream.config.profile == 7 &&
                stream.enhancementLayerPresent &&
                enhancementLayerComposed == true
}

private const val MIN_DOVI_CONFIG_BYTES = 4
