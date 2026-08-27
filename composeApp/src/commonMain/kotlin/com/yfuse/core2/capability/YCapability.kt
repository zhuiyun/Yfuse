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
    Vc1,
    Mpeg2,
    ProRes,
    Unknown,
}

enum class YAudioCodec {
    Aac,
    Alac,
    Mp3,
    Ac3,
    Eac3,
    Eac3Joc,
    Flac,
    Opus,
    TrueHd,
    TrueHdAtmos,
    Dts,
    DtsHd,
    DtsX,
    Pcm,
    Unknown,
}

enum class YAudioOutputPath {
    None,
    DecodePcm,
    Passthrough,
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
    val secureDecodeRequired: Boolean = false,
    val surfaceOutputRequired: Boolean = true,
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
    /** Raw platform profile -> maximum advertised level, retained without semantic guessing. */
    val rawProfileLevels: Map<Int, Int> = emptyMap(),
    /** Semantic DV bitstream profiles derived from the Android profile constants. */
    val dolbyVisionProfiles: Set<Int> = emptySet(),
    val maxWidth: Int = 0,
    val maxHeight: Int = 0,
    val maxFrameRate: Double = 0.0,
    val maxBitDepth: Int = 8,
    val tunneledPlayback: Boolean = false,
    val adaptivePlayback: Boolean = false,
    val securePlayback: Boolean = false,
    val lowLatencyPlayback: Boolean = false,
    val surfaceOutput: Boolean = true,
) {
    fun supports(requirement: YVideoRequirement): Boolean {
        if (codec != requirement.codec) return false
        if (requirement.secureDecodeRequired && !securePlayback) return false
        if (requirement.surfaceOutputRequired && !surfaceOutput) return false
        if (requirement.hdrType !in hdrTypes) return false
        if (
            requirement.hdrType == YHdrType.DolbyVision &&
            requirement.dolbyVisionProfile != null &&
            requirement.dolbyVisionProfile !in dolbyVisionProfiles
        ) {
            return false
        }
        val nativeOrientationFits =
            requirement.width.fitsWithin(maxWidth) && requirement.height.fitsWithin(maxHeight)
        val rotatedOrientationFits =
            requirement.height.fitsWithin(maxWidth) && requirement.width.fitsWithin(maxHeight)
        if (!nativeOrientationFits && !rotatedOrientationFits) return false
        if (maxFrameRate > 0.0 && requirement.frameRate > maxFrameRate) return false
        if (maxBitDepth > 0 && requirement.bitDepth > maxBitDepth) return false
        return true
    }
}

private fun Int.fitsWithin(maximum: Int): Boolean = maximum <= 0 || this <= maximum

data class YDeviceCapabilities(
    val videoDecoders: List<YVideoDecoderCapability>,
    val audioDecoders: Set<YAudioCodec> = emptySet(),
    val audioPassthrough: Set<YAudioCodec> = emptySet(),
    val displayHdrTypes: Set<YHdrType> = setOf(YHdrType.Sdr),
    val supportsSurfaceDirect: Boolean = true,
    val supportsTunnel: Boolean = videoDecoders.any { it.tunneledPlayback },
    val supportsFrameRateSwitching: Boolean = false,
) {
    fun bestDecoder(requirement: YVideoRequirement): YVideoDecoderCapability? =
        videoDecoders
            .asSequence()
            .filter { it.supports(requirement) }
            .sortedWith(
                compareByDescending<YVideoDecoderCapability> { it.tunneledPlayback }
                    .thenByDescending { it.adaptivePlayback },
            ).firstOrNull()

    fun audioOutputPath(requirement: YAudioRequirement?): YAudioOutputPath {
        val codec = requirement?.codec ?: return YAudioOutputPath.None
        return when {
            codec in audioPassthrough -> YAudioOutputPath.Passthrough
            audioDecoders.supportsDecoded(codec) -> YAudioOutputPath.DecodePcm
            else -> YAudioOutputPath.None
        }
    }

    fun supportsAudio(requirement: YAudioRequirement?): Boolean =
        requirement == null || audioOutputPath(requirement) != YAudioOutputPath.None

    fun supportsDisplayHdr(type: YHdrType): Boolean = type == YHdrType.Sdr || type in displayHdrTypes

    companion object {
        fun conservative(): YDeviceCapabilities =
            YDeviceCapabilities(
                videoDecoders = emptyList(),
                audioDecoders = emptySet(),
                audioPassthrough = emptySet(),
                displayHdrTypes = setOf(YHdrType.Sdr),
                supportsSurfaceDirect = true,
                supportsTunnel = false,
                supportsFrameRateSwitching = false,
            )
    }
}

private fun Set<YAudioCodec>.supportsDecoded(codec: YAudioCodec): Boolean =
    codec in this ||
        (codec == YAudioCodec.Eac3Joc && YAudioCodec.Eac3 in this) ||
        (codec == YAudioCodec.TrueHdAtmos && YAudioCodec.TrueHd in this) ||
        (codec == YAudioCodec.DtsX && YAudioCodec.DtsHd in this)

/** Platform-specific capability snapshots plug into the route planner through this interface. */
fun interface YCapabilityProvider {
    fun current(): YDeviceCapabilities
}
