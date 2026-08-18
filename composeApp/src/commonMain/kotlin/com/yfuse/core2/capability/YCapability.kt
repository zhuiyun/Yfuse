package com.yfuse.core2.capability

/** Container identity used by the Core2 route planner, independent of any concrete extractor. */
enum class YContainer {
    Mp4,
    Matroska,
    WebM,
    MpegTs,
    M2ts,
    Mov,
    Iso,
    Bdmv,
    Unknown,
}

enum class YVideoCodec {
    H264,
    H265,
    Av1,
    Vp9,
    Mpeg2,
    ProRes,
    Unknown,
}

enum class YAudioCodec {
    Aac,
    Ac3,
    Eac3,
    Flac,
    Opus,
    TrueHd,
    Dts,
    DtsHd,
    Pcm,
    Unknown,
}

enum class YHdrType {
    Sdr,
    Hdr10,
    Hdr10Plus,
    Hlg,
    DolbyVision,
}

data class YVideoRequirement(
    val codec: YVideoCodec,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val bitDepth: Int = 8,
    val hdrType: YHdrType = YHdrType.Sdr,
    /** Semantic Dolby Vision bitstream profile (4/5/7/8/9/10), not Android's raw profile bit. */
    val dolbyVisionProfile: Int? = null,
)

data class YAudioRequirement(
    val codec: YAudioCodec,
    val channelCount: Int = 2,
    val sampleRate: Int = 48_000,
)

data class YVideoDecoderCapability(
    val name: String,
    val codec: YVideoCodec,
    val hdrTypes: Set<YHdrType> = setOf(YHdrType.Sdr),
    /** Raw Android codec profile constants retained for diagnostics/quirk matching. */
    val rawProfiles: Set<Int> = emptySet(),
    /** Semantic DV bitstream profiles derived from the Android profile constants. */
    val dolbyVisionProfiles: Set<Int> = emptySet(),
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Double = 0.0,
    val maxBitDepth: Int = 8,
    val tunneledPlayback: Boolean = false,
    val adaptivePlayback: Boolean = false,
) {
    fun supports(requirement: YVideoRequirement): Boolean {
        if (codec != requirement.codec) return false
        if (requirement.hdrType !in hdrTypes) return false
        if (
            requirement.hdrType == YHdrType.DolbyVision &&
            requirement.dolbyVisionProfile != null &&
            requirement.dolbyVisionProfile !in dolbyVisionProfiles
        ) {
            return false
        }
        if (maxWidth > 0 && requirement.width > maxWidth) return false
        if (maxHeight > 0 && requirement.height > maxHeight) return false
        if (maxFrameRate > 0.0 && requirement.frameRate > maxFrameRate) return false
        if (maxBitDepth > 0 && requirement.bitDepth > maxBitDepth) return false
        return true
    }
}

data class YDeviceCapabilities(
    val videoDecoders: List<YVideoDecoderCapability>,
    val audioDecoders: Set<YAudioCodec> = emptySet(),
    val displayHdrTypes: Set<YHdrType> = setOf(YHdrType.Sdr),
    val supportsSurfaceDirect: Boolean = true,
    val supportsTunnel: Boolean = videoDecoders.any { it.tunneledPlayback },
) {
    fun bestDecoder(requirement: YVideoRequirement): YVideoDecoderCapability? =
        videoDecoders
            .asSequence()
            .filter { it.supports(requirement) }
            .sortedWith(
                compareByDescending<YVideoDecoderCapability> { it.tunneledPlayback }
                    .thenByDescending { it.adaptivePlayback },
            ).firstOrNull()

    fun supportsAudio(requirement: YAudioRequirement?): Boolean =
        requirement == null || requirement.codec in audioDecoders

    fun supportsDisplayHdr(type: YHdrType): Boolean =
        type == YHdrType.Sdr || type in displayHdrTypes

    companion object {
        fun conservative(): YDeviceCapabilities =
            YDeviceCapabilities(
                videoDecoders = emptyList(),
                audioDecoders = emptySet(),
                displayHdrTypes = setOf(YHdrType.Sdr),
                supportsSurfaceDirect = true,
                supportsTunnel = false,
            )
    }
}

/** Platform-specific capability snapshots plug into the route planner through this interface. */
fun interface YCapabilityProvider {
    fun current(): YDeviceCapabilities
}
