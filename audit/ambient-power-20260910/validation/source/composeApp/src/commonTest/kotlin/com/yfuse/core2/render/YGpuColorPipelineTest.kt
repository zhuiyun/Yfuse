package com.yfuse.core2.render

import com.yfuse.core2.capability.YHdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YGpuColorPipelineTest {
    @Test
    fun hdr10_to_sdr_uses_bt2390_bt2020_mapping_and_dither() {
        val config =
            gpuColorPipelineConfig(
                sourceHdrType = YHdrType.Hdr10,
                outputHdrType = YHdrType.Sdr,
                bitDepth = 10,
                staticPeakNits = 4_000f,
                displayPeakNits = 350f,
                scalingFilter = YScalingFilter.Lanczos,
            )

        assertEquals(YGpuColorTransfer.Pq, config.sourceTransfer)
        assertEquals(YGpuColorTransfer.Sdr, config.outputTransfer)
        assertEquals(YGpuColorPrimaries.Bt2020, config.sourcePrimaries)
        assertEquals(YGpuColorPrimaries.Bt709, config.outputPrimaries)
        assertEquals(YToneMapper.Bt2390, config.toneMapper)
        assertEquals(YScalingFilter.Lanczos, config.scalingFilter)
        assertTrue(config.ditherStrength > 0f)
    }

    @Test
    fun hlg_to_hlg_preserves_dynamic_range_without_tone_mapping() {
        val config =
            gpuColorPipelineConfig(
                sourceHdrType = YHdrType.Hlg,
                outputHdrType = YHdrType.Hlg,
                bitDepth = 10,
                staticPeakNits = null,
                displayPeakNits = null,
                scalingFilter = YScalingFilter.Bicubic,
            )

        assertEquals(YGpuColorTransfer.Hlg, config.sourceTransfer)
        assertEquals(YGpuColorTransfer.Hlg, config.outputTransfer)
        assertEquals(null, config.toneMapper)
    }

    @Test
    fun hdr_output_uses_bt2390_when_mastering_peak_exceeds_the_display() {
        val config =
            gpuColorPipelineConfig(
                sourceHdrType = YHdrType.Hdr10,
                outputHdrType = YHdrType.Hdr10,
                bitDepth = 10,
                staticPeakNits = 4_000f,
                displayPeakNits = 1_000f,
                scalingFilter = YScalingFilter.Lanczos,
            )

        assertEquals(YGpuColorTransfer.Pq, config.outputTransfer)
        assertEquals(YToneMapper.Bt2390, config.toneMapper)
    }
}
