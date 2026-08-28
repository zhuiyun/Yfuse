package com.yfuse.core2.render

import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.demux.YChromaLocation
import com.yfuse.core2.demux.YColorMatrix
import com.yfuse.core2.demux.YColorRange
import com.yfuse.core2.demux.YVideoGeometry
import com.yfuse.core2.hdr.YHdrStaticMetadata
import com.yfuse.core2.hdr.YHdr10PlusSceneMetadata

enum class YGpuColorTransfer {
    Sdr,
    Pq,
    Hlg,
}

enum class YGpuColorPrimaries {
    Bt709,
    Bt2020,
    DisplayP3,
}

data class YGpuColorPipelineConfig(
    val sourceTransfer: YGpuColorTransfer,
    val outputTransfer: YGpuColorTransfer,
    val sourcePrimaries: YGpuColorPrimaries,
    val outputPrimaries: YGpuColorPrimaries,
    val sourceBitDepth: Int,
    val sourcePeakNits: Float,
    val displayPeakNits: Float,
    val paperWhiteNits: Float = 203f,
    val toneMapper: YToneMapper? = null,
    val scalingFilter: YScalingFilter = YScalingFilter.Bilinear,
    val debandStrength: Float = 0.15f,
    val ditherStrength: Float = 1f,
    val sourceRange: YColorRange = YColorRange.Limited,
    val sourceMatrix: YColorMatrix = YColorMatrix.Unspecified,
    val chromaLocation: YChromaLocation = YChromaLocation.Unspecified,
    val geometry: YVideoGeometry = YVideoGeometry(),
    val hdrStaticMetadata: YHdrStaticMetadata? = null,
    val hdr10PlusSceneMetadata: YHdr10PlusSceneMetadata? = null,
) {
    init {
        require(sourceBitDepth in 8..16)
        require(sourcePeakNits.isFinite() && sourcePeakNits in 80f..10_000f)
        require(displayPeakNits.isFinite() && displayPeakNits in 80f..10_000f)
        require(paperWhiteNits.isFinite() && paperWhiteNits in 80f..500f)
        require(debandStrength.isFinite() && debandStrength in 0f..1f)
        require(ditherStrength.isFinite() && ditherStrength in 0f..2f)
    }
}

fun gpuColorPipelineConfig(
    sourceHdrType: YHdrType,
    outputHdrType: YHdrType,
    bitDepth: Int,
    staticPeakNits: Float?,
    displayPeakNits: Float?,
    scalingFilter: YScalingFilter,
    sourceRange: YColorRange = YColorRange.Unspecified,
    sourceMatrix: YColorMatrix = YColorMatrix.Unspecified,
    sourcePrimaries: com.yfuse.core2.demux.YColorPrimaries = com.yfuse.core2.demux.YColorPrimaries.Unspecified,
    chromaLocation: YChromaLocation = YChromaLocation.Unspecified,
    geometry: YVideoGeometry = YVideoGeometry(),
    hdrStaticMetadata: YHdrStaticMetadata? = null,
): YGpuColorPipelineConfig {
    val sourceTransfer = sourceHdrType.toGpuTransfer()
    val outputTransfer = outputHdrType.toGpuTransfer()
    val sourcePeak = staticPeakNits?.takeIf { it.isFinite() && it > 0f } ?: sourceHdrType.defaultPeakNits()
    val outputPeak = displayPeakNits?.takeIf { it.isFinite() && it > 0f } ?: outputHdrType.defaultPeakNits()
    return YGpuColorPipelineConfig(
        sourceTransfer = sourceTransfer,
        outputTransfer = outputTransfer,
        sourcePrimaries =
            when (sourcePrimaries) {
                com.yfuse.core2.demux.YColorPrimaries.Bt709 -> YGpuColorPrimaries.Bt709
                com.yfuse.core2.demux.YColorPrimaries.Bt2020 -> YGpuColorPrimaries.Bt2020
                com.yfuse.core2.demux.YColorPrimaries.DisplayP3 -> YGpuColorPrimaries.DisplayP3
                com.yfuse.core2.demux.YColorPrimaries.Unspecified ->
                    if (sourceHdrType == YHdrType.Sdr) YGpuColorPrimaries.Bt709 else YGpuColorPrimaries.Bt2020
            },
        outputPrimaries = if (outputHdrType == YHdrType.Sdr) YGpuColorPrimaries.Bt709 else YGpuColorPrimaries.Bt2020,
        sourceBitDepth = bitDepth.coerceIn(8, 16),
        sourcePeakNits = sourcePeak.coerceIn(80f, 10_000f),
        displayPeakNits = outputPeak.coerceIn(80f, 10_000f),
        toneMapper =
            YToneMapper.Bt2390.takeIf {
                sourceTransfer != YGpuColorTransfer.Sdr &&
                    (outputTransfer == YGpuColorTransfer.Sdr || sourcePeak > outputPeak * 1.05f)
            },
        scalingFilter = scalingFilter,
        debandStrength = if (bitDepth <= 8) 0.25f else 0.12f,
        ditherStrength = if (outputHdrType == YHdrType.Sdr || bitDepth <= 8) 1f else 0.35f,
        sourceRange = sourceRange.takeUnless { it == YColorRange.Unspecified } ?: YColorRange.Limited,
        sourceMatrix =
            sourceMatrix.takeUnless { it == YColorMatrix.Unspecified }
                ?: if (sourceHdrType == YHdrType.Sdr) YColorMatrix.Bt709 else YColorMatrix.Bt2020,
        chromaLocation = chromaLocation,
        geometry = geometry,
        hdrStaticMetadata = hdrStaticMetadata,
    )
}

private fun YHdrType.toGpuTransfer(): YGpuColorTransfer =
    when (this) {
        YHdrType.Hlg -> YGpuColorTransfer.Hlg
        YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.DolbyVision -> YGpuColorTransfer.Pq
        YHdrType.Sdr -> YGpuColorTransfer.Sdr
    }

private fun YHdrType.defaultPeakNits(): Float =
    when (this) {
        YHdrType.Hdr10, YHdrType.Hdr10Plus, YHdrType.DolbyVision -> 1_000f
        YHdrType.Hlg -> 1_000f
        YHdrType.Sdr -> 203f
    }
