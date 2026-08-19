package com.yfuse.core2.legacy

import com.yfuse.core2.api.YPlaybackRoute
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidMpvCore2FallbackFactoryTest {
    @Test
    fun gpu_route_is_preserved_when_mpv_reports_hardware_decode() {
        assertEquals(
            YPlaybackRoute.GpuEnhanced,
            resolvedMpvFallbackRoute(
                plannedRoute = YPlaybackRoute.GpuEnhanced,
                decoderLabel = "硬件解码 · mediacodec",
            ),
        )
    }

    @Test
    fun runtime_software_decode_is_reported_as_software_fallback() {
        assertEquals(
            YPlaybackRoute.SoftwareFallback,
            resolvedMpvFallbackRoute(
                plannedRoute = YPlaybackRoute.GpuEnhanced,
                decoderLabel = "FFmpeg 软件解码",
            ),
        )
        assertEquals(
            YPlaybackRoute.SoftwareFallback,
            resolvedMpvFallbackRoute(
                plannedRoute = YPlaybackRoute.SoftwareFallback,
                decoderLabel = "等待解码器",
            ),
        )
    }
}
