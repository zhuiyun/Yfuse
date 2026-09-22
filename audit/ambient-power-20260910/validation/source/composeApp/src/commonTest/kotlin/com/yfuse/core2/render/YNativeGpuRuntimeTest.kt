package com.yfuse.core2.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YNativeGpuRuntimeTest {
    @Test
    fun importedHardwareBufferIsWarmupOnlyUntilARealFrameIsMeasured() {
        val probe = probe(WARMUP_FEATURE_MASK)

        assertTrue(probe.supportsWarmup)
        assertFalse(probe.canClaimNativeVulkan)
        assertEquals(YNativeGpuRequirement.SwapchainPresentation, probe.firstMissingRequirement())
        assertFalse(probe.toGpuCapabilities().nativeVulkanExecutorVerified)
    }

    @Test
    fun verifiedExecutorRequiresPresentationDecodedFrameAndMeasurement() {
        val probe =
            probe(
                STATIC_READY_FEATURE_MASK or
                    YNativeGpuFeature.SwapchainPresented.mask or
                    YNativeGpuFeature.DecodedFramePresented.mask or
                    YNativeGpuFeature.OutputMeasured.mask or
                    YNativeGpuFeature.ImageReaderDecodedFrame.mask,
            )

        assertTrue(probe.canClaimNativeVulkan)
        assertTrue(probe.toGpuCapabilities().nativeVulkanExecutorVerified)
        assertEquals(null, probe.firstMissingRequirement())
    }

    @Test
    fun oldAndroidOrMissingYcbcrCannotEnterWarmup() {
        assertFalse(probe(WARMUP_FEATURE_MASK, apiLevel = 27).supportsWarmup)
        assertFalse(
            probe(WARMUP_FEATURE_MASK and YNativeGpuFeature.SamplerYcbcrConversion.mask.inv()).supportsWarmup,
        )
    }

    @Test
    fun probeDoesNotAdvertiseUnimplementedHdrProcessing() {
        val capabilities = probe(WARMUP_FEATURE_MASK).toGpuCapabilities()

        assertFalse(capabilities.supportsHdrInput)
        assertFalse(capabilities.supportsHdrOutput)
        assertTrue(capabilities.toneMappers.isEmpty())
    }

    @Test
    fun hdrProcessingRequiresMeasuredP010Input() {
        val verifiedMask =
            STATIC_READY_FEATURE_MASK or
                YNativeGpuFeature.SwapchainPresented.mask or
                YNativeGpuFeature.DecodedFramePresented.mask or
                YNativeGpuFeature.OutputMeasured.mask or
                YNativeGpuFeature.ImageReaderDecodedFrame.mask

        assertFalse(probe(verifiedMask).canProcessHdr)
        assertTrue(probe(verifiedMask or YNativeGpuFeature.P010Input.mask).canProcessHdr)
    }

    private fun probe(
        mask: Long,
        apiLevel: Int = 35,
    ) = YNativeGpuRuntimeProbe(
        platformApiLevel = apiLevel,
        nativeApiVersion = NATIVE_GPU_API_VERSION,
        featureMask = mask,
    )
}

private val WARMUP_FEATURE_MASK =
    listOf(
        YNativeGpuFeature.VulkanLoader,
        YNativeGpuFeature.VulkanInstance,
        YNativeGpuFeature.PhysicalDevice,
        YNativeGpuFeature.LogicalDevice,
        YNativeGpuFeature.Swapchain,
        YNativeGpuFeature.HardwareBuffer,
        YNativeGpuFeature.SamplerYcbcrConversion,
        YNativeGpuFeature.HardwareBufferImported,
    ).fold(0L) { mask, feature -> mask or feature.mask }

private val STATIC_READY_FEATURE_MASK =
    WARMUP_FEATURE_MASK or
        listOf(
            YNativeGpuFeature.Bt2390Shader,
            YNativeGpuFeature.GamutMappingShader,
            YNativeGpuFeature.HighQualityScalingShader,
            YNativeGpuFeature.DebandDitherShader,
            YNativeGpuFeature.DisplayTiming,
        ).fold(0L) { mask, feature -> mask or feature.mask }
