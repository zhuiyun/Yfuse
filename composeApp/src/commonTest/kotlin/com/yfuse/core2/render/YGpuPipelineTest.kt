package com.yfuse.core2.render

import com.yfuse.core2.capability.YHdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YGpuPipelineTest {
    @Test
    fun `keeps native direct path when no processing is necessary`() {
        val plan =
            YGpuPipelinePlanner.plan(
                request = request(sourceHdr = YHdrType.Hdr10, displayHdr = setOf(YHdrType.Hdr10)),
                capabilities = capabilities(),
            )

        assertNull(plan)
    }

    @Test
    fun `selects Vulkan BT2390 and dithering for HDR to SDR`() {
        val plan =
            requireNotNull(
                YGpuPipelinePlanner.plan(
                    request = request(sourceHdr = YHdrType.Hdr10, displayHdr = setOf(YHdrType.Sdr)),
                    capabilities = capabilities(tenBit = false),
                ),
            )

        assertEquals(YGpuBackend.Vulkan, plan.backend)
        assertEquals(YHdrType.Sdr, plan.outputHdrType)
        assertEquals(YToneMapper.Bt2390, plan.toneMapper)
        assertTrue(plan.gamutMapping)
        assertTrue(plan.dithering)
    }

    @Test
    fun `refuses dishonest HDR fallback without a tone mapper`() {
        val plan =
            YGpuPipelinePlanner.plan(
                request = request(sourceHdr = YHdrType.DolbyVision, displayHdr = setOf(YHdrType.Sdr)),
                capabilities = capabilities().copy(toneMappers = emptySet()),
            )

        assertNull(plan)
    }

    @Test
    fun `ordinary display scaling remains on native Surface path`() {
        val plan =
            YGpuPipelinePlanner.plan(
                request =
                    request(YHdrType.Sdr, setOf(YHdrType.Sdr)).copy(
                        outputWidth = 1_920,
                        outputHeight = 1_080,
                    ),
                capabilities = capabilities(),
            )

        assertNull(plan)
    }

    private fun request(
        sourceHdr: YHdrType,
        displayHdr: Set<YHdrType>,
    ): YGpuRenderRequest =
        YGpuRenderRequest(
            sourceHdrType = sourceHdr,
            displayHdrTypes = displayHdr,
            directPresentationSupported = true,
            sourceWidth = 3840,
            sourceHeight = 2160,
            outputWidth = 3840,
            outputHeight = 2160,
        )

    private fun capabilities(tenBit: Boolean = true): YGpuCapabilities =
        YGpuCapabilities(
            backends = setOf(YGpuBackend.Vulkan, YGpuBackend.MpvGpu),
            toneMappers = setOf(YToneMapper.Bt2390, YToneMapper.Hable),
            scalingFilters = setOf(YScalingFilter.Lanczos),
            supportsHdrInput = true,
            supportsHdrOutput = true,
            supportsTenBitOutput = tenBit,
        )
}
