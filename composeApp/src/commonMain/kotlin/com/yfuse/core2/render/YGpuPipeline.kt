package com.yfuse.core2.render

import com.yfuse.core2.capability.YHdrType

enum class YGpuBackend {
    Vulkan,
    MpvGpu,
}

enum class YToneMapper {
    Bt2390,
    Hable,
    Reinhard,
    Mobius,
}

enum class YScalingFilter {
    Bilinear,
    Bicubic,
    Lanczos,
}

data class YGpuCapabilities(
    val backends: Set<YGpuBackend>,
    val toneMappers: Set<YToneMapper> = emptySet(),
    val scalingFilters: Set<YScalingFilter> = setOf(YScalingFilter.Bilinear),
    val supportsHdrInput: Boolean = false,
    val supportsHdrOutput: Boolean = false,
    val supportsTenBitOutput: Boolean = false,
)

data class YGpuRenderRequest(
    val sourceHdrType: YHdrType,
    val displayHdrTypes: Set<YHdrType>,
    val directPresentationSupported: Boolean,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    /** Ordinary display scaling stays in Surface/HWC; only an explicit quality request enters GPU. */
    val requireHighQualityScaling: Boolean = false,
    val requireGamutMapping: Boolean = false,
)

data class YGpuRenderPlan(
    val backend: YGpuBackend,
    val outputHdrType: YHdrType,
    val toneMapper: YToneMapper?,
    val scalingFilter: YScalingFilter,
    val gamutMapping: Boolean,
    val dithering: Boolean,
    val reason: String,
)

/**
 * Selects the explicit high-quality GPU path. A null result means direct Surface presentation is
 * both possible and sufficient, preserving zero-copy hardware decode for the common path.
 */
object YGpuPipelinePlanner {
    fun plan(
        request: YGpuRenderRequest,
        capabilities: YGpuCapabilities,
    ): YGpuRenderPlan? {
        val displayCanPresentSource =
            request.sourceHdrType == YHdrType.Sdr || request.sourceHdrType in request.displayHdrTypes
        val scalingRequired =
            request.requireHighQualityScaling &&
                request.sourceWidth > 0 &&
                request.sourceHeight > 0 &&
                request.outputWidth > 0 &&
                request.outputHeight > 0 &&
                (request.sourceWidth != request.outputWidth || request.sourceHeight != request.outputHeight)
        val processingRequired =
            !request.directPresentationSupported ||
                !displayCanPresentSource ||
                request.requireGamutMapping ||
                scalingRequired
        if (!processingRequired) return null

        val backend =
            when {
                YGpuBackend.Vulkan in capabilities.backends -> YGpuBackend.Vulkan
                YGpuBackend.MpvGpu in capabilities.backends -> YGpuBackend.MpvGpu
                else -> return null
            }
        val canRetainHdr =
            request.sourceHdrType != YHdrType.Sdr &&
                displayCanPresentSource &&
                capabilities.supportsHdrInput &&
                capabilities.supportsHdrOutput
        val outputHdrType = if (canRetainHdr) request.sourceHdrType else YHdrType.Sdr
        val needsToneMapping = request.sourceHdrType != YHdrType.Sdr && outputHdrType == YHdrType.Sdr
        val toneMapper =
            if (needsToneMapping) {
                preferredToneMappers.firstOrNull(capabilities.toneMappers::contains) ?: return null
            } else {
                null
            }
        val scalingFilter =
            preferredScalingFilters.firstOrNull(capabilities.scalingFilters::contains)
                ?: YScalingFilter.Bilinear
        return YGpuRenderPlan(
            backend = backend,
            outputHdrType = outputHdrType,
            toneMapper = toneMapper,
            scalingFilter = scalingFilter,
            gamutMapping = request.requireGamutMapping || needsToneMapping,
            dithering = !capabilities.supportsTenBitOutput && request.sourceHdrType != YHdrType.Sdr,
            reason =
                when {
                    needsToneMapping -> "Tone-map ${request.sourceHdrType} to SDR through $backend"
                    request.requireGamutMapping -> "Apply explicit color-gamut conversion through $backend"
                    scalingRequired -> "Scale through $backend using $scalingFilter"
                    else -> "Direct presentation unavailable; render through $backend"
                },
        )
    }
}

private val preferredToneMappers =
    listOf(YToneMapper.Bt2390, YToneMapper.Mobius, YToneMapper.Hable, YToneMapper.Reinhard)
private val preferredScalingFilters =
    listOf(YScalingFilter.Lanczos, YScalingFilter.Bicubic, YScalingFilter.Bilinear)
