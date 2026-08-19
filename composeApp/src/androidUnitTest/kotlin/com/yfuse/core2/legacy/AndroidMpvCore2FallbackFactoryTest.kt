package com.yfuse.core2.legacy

import com.yfuse.core2.api.YDiscKind
import com.yfuse.core2.api.YDiscMedia
import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
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

    @Test
    fun direct_disc_builds_a_real_libbluray_source_and_bounded_fallback_plan() {
        val disc = YDiscMedia(kind = YDiscKind.Bdmv, container = "BDMV", label = "Blu-ray")
        val item =
            YMediaItem(
                id = "disc-1",
                uri = "yfusebdmv://42",
                title = "Movie",
                disc = disc,
            )

        val media = item.toDiscPlayerMediaItem()
        val hardwarePlan = core2DiscCompatibilityPlan(disc, forceSoftwareDecode = false)
        val softwarePlan = core2DiscCompatibilityPlan(disc, forceSoftwareDecode = true)

        assertEquals("yfusebdmv://42", media.url)
        assertEquals(true, media.activeVersion?.discSource)
        assertEquals(YPlaybackRoute.GpuEnhanced, hardwarePlan.route)
        assertEquals(YDemuxPath.Enhanced, hardwarePlan.demuxPath)
        assertEquals(YDecodePath.Hardware, hardwarePlan.decodePath)
        assertEquals(YPlaybackRoute.SoftwareFallback, softwarePlan.route)
        assertEquals(YDemuxPath.Software, softwarePlan.demuxPath)
        assertEquals(YDecodePath.Software, softwarePlan.decodePath)
    }
}
