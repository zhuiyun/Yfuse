package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YDolbyVisionNalEvidence
import com.yfuse.core2.capability.YHdrType

enum class YDolbyVisionCodecFamily {
    Hevc,
    Avc,
    Av1,
    Unknown,
}

/** Source classification; Unknown is intentionally different from FEL. */
enum class YDolbyVisionEnhancementLayerKind {
    None,
    Mel,
    Fel,
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
     * 10.1/10.4). Profile 7 has an HDR10 base layer by definition; stripping EL/RPU is therefore a
     * safe HDR10 fallback, but never proof that MEL/FEL composition occurred.
     */
    val compatibleBaseHdr: YHdrType?
        get() {
            if (!baseLayerPresent) return null
            if (profile == 7) return YHdrType.Hdr10
            if (profile !in setOf(8, 10)) return null
            return when (baseLayerCompatibilityId) {
                1 -> YHdrType.Hdr10
                2 -> YHdrType.Sdr
                4 -> YHdrType.Hlg
                else -> null
            }
        }

    /** Encodes the ISOBMFF Dolby Vision decoder configuration record used as Android csd-2. */
    fun toConfigurationBytes(): ByteArray {
        require(versionMajor in 0..0xff)
        require(versionMinor in 0..0xff)
        require(profile in 0..0x7f)
        require(level in 0..0x3f)
        require(baseLayerCompatibilityId in 0..0x0f)
        require(metadataCompression in 0..0x03)

        val packed =
            (profile shl 9) or
                (level shl 3) or
                (if (rpuPresent) 0x04 else 0) or
                (if (enhancementLayerPresent) 0x02 else 0) or
                (if (baseLayerPresent) 0x01 else 0)
        return byteArrayOf(
            versionMajor.toByte(),
            versionMinor.toByte(),
            (packed ushr 8).toByte(),
            packed.toByte(),
            ((baseLayerCompatibilityId shl 4) or (metadataCompression shl 2)).toByte(),
        )
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
    /** Trusted parser/container evidence. EL-present flags alone must leave this null/Unknown. */
    val parsedEnhancementLayerKind: YDolbyVisionEnhancementLayerKind? = null,
) {
    val rpuPresent: Boolean get() = config.rpuPresent || observedNals.rpuPresent
    val enhancementLayerPresent: Boolean
        get() = config.enhancementLayerPresent || observedNals.enhancementLayerPresent
    val baseLayerPresent: Boolean get() = config.baseLayerPresent

    val enhancementLayerKind: YDolbyVisionEnhancementLayerKind
        get() =
            when {
                !enhancementLayerPresent -> YDolbyVisionEnhancementLayerKind.None
                config.profile != 7 -> YDolbyVisionEnhancementLayerKind.Unknown
                parsedEnhancementLayerKind == YDolbyVisionEnhancementLayerKind.Mel ->
                    YDolbyVisionEnhancementLayerKind.Mel
                parsedEnhancementLayerKind == YDolbyVisionEnhancementLayerKind.Fel ->
                    YDolbyVisionEnhancementLayerKind.Fel
                else -> YDolbyVisionEnhancementLayerKind.Unknown
            }

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
                stream.enhancementLayerKind == YDolbyVisionEnhancementLayerKind.Fel &&
                enhancementLayerComposed == true
}

private const val MIN_DOVI_CONFIG_BYTES = 4
