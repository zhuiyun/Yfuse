package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
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
    fun runtime_fallback_forces_software_decode_and_gpu_rendering() {
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
        assertEquals(YDemuxPath.Software, fallback.demuxPath)
        assertEquals(YDecodePath.Software, fallback.decodePath)
        assertEquals(YRenderPath.Gpu, fallback.renderPath)
        assertEquals(null, fallback.decoderName)
        assertFalse(fallback.nativeAudio)
        assertEquals("decoder failed", fallback.reason)
    }
}
