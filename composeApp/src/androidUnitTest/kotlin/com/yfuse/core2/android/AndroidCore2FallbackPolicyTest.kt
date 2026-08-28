package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.render.NATIVE_GPU_API_VERSION
import com.yfuse.core2.render.YNativeGpuFeature
import com.yfuse.core2.render.YNativeGpuRuntimeProbe
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2FallbackPolicyTest {
    @Test
    fun only_local_pipeline_failures_enter_software_fallback() {
        assertTrue(YPlaybackFailureCategory.Decoder.allowsCore2LocalSoftwareFallback())
        assertTrue(YPlaybackFailureCategory.Renderer.allowsCore2LocalSoftwareFallback())
        assertTrue(YPlaybackFailureCategory.Container.allowsCore2LocalSoftwareFallback())
        assertTrue(YPlaybackFailureCategory.AudioSink.allowsCore2LocalSoftwareFallback())
        assertTrue(YPlaybackFailureCategory.Unknown.allowsCore2LocalSoftwareFallback())
        assertTrue((null as YPlaybackFailureCategory?).allowsCore2LocalSoftwareFallback())

        assertFalse(YPlaybackFailureCategory.Network.allowsCore2LocalSoftwareFallback())
        assertFalse(YPlaybackFailureCategory.Authorization.allowsCore2LocalSoftwareFallback())
        assertFalse(YPlaybackFailureCategory.Drm.allowsCore2LocalSoftwareFallback())
    }

    @Test
    fun runtime_fallback_uses_owned_software_decode_and_tone_mapping() {
        val original =
            YPlaybackPlan(
                route = YPlaybackRoute.NativeDirect,
                demuxPath = YDemuxPath.Platform,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.SurfaceDirect,
                outputHdrType = YHdrType.Hdr10,
                decoderName = "hardware.decoder",
                nativeAudio = true,
                reason = "native",
            )

        val fallback = original.toSoftwareFallbackPlan("decoder failed")

        assertEquals(YPlaybackRoute.SoftwareFallback, fallback.route)
        assertEquals(YDemuxPath.Enhanced, fallback.demuxPath)
        assertEquals(YDecodePath.Software, fallback.decodePath)
        assertEquals(YRenderPath.Gpu, fallback.renderPath)
        assertEquals(YHdrType.Sdr, fallback.outputHdrType)
        assertEquals(null, fallback.decoderName)
        assertTrue(fallback.nativeAudio)
        assertTrue(fallback.softwareVideoToneMap)
        assertEquals("decoder failed", fallback.reason)
    }

    @Test
    fun gpu_fallback_reports_the_first_unproven_native_boundary() {
        val plan =
            YPlaybackPlan(
                route = YPlaybackRoute.GpuEnhanced,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.Gpu,
                outputHdrType = YHdrType.Sdr,
                reason = "tone map through GPU",
            )
        val probe =
            YNativeGpuRuntimeProbe(
                platformApiLevel = 35,
                nativeApiVersion = NATIVE_GPU_API_VERSION,
                featureMask = YNativeGpuFeature.VulkanLoader.mask,
            )

        val annotated = plan.withNativeGpuFallbackTruth(probe)

        assertTrue(annotated.reason.contains("VulkanInstance"))
        assertEquals(YPlaybackRoute.GpuEnhanced, annotated.route)
    }
}
