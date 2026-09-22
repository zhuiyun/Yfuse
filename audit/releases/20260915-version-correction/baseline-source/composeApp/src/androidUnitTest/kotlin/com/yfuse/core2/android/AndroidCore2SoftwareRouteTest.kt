package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2SoftwareRouteTest {
    @Test
    fun `SDR FFmpeg video software route is executable`() {
        assertTrue(decision(YHdrType.Sdr, YDecodePath.Software, YRenderPath.Gpu).ffmpegSoftwareExecutable)
    }

    @Test
    fun `HDR FFmpeg video waits for the owned tone mapper`() {
        assertFalse(decision(YHdrType.Hdr10, YDecodePath.Software, YRenderPath.Gpu).ffmpegSoftwareExecutable)
        assertTrue(
            decision(
                YHdrType.Hdr10,
                YDecodePath.Software,
                YRenderPath.Gpu,
                softwareVideoToneMap = true,
            ).ffmpegSoftwareExecutable,
        )
    }

    @Test
    fun `oversized FFmpeg software video fails closed`() {
        assertFalse(
            decision(
                YHdrType.Sdr,
                YDecodePath.Software,
                YRenderPath.Gpu,
                width = 7680,
                height = 4320,
            ).ffmpegSoftwareExecutable,
        )
    }

    @Test
    fun `MediaCodec video plus FFmpeg audio requires direct surface presentation`() {
        assertTrue(decision(YHdrType.Sdr, YDecodePath.Hardware, YRenderPath.SurfaceDirect).ffmpegSoftwareExecutable)
        assertFalse(decision(YHdrType.Sdr, YDecodePath.Hardware, YRenderPath.Gpu).ffmpegSoftwareExecutable)
    }

    private fun decision(
        hdrType: YHdrType,
        decodePath: YDecodePath,
        renderPath: YRenderPath,
        width: Int = 1920,
        height: Int = 1080,
        softwareVideoToneMap: Boolean = false,
    ): YCore2RouteDecision {
        val request =
            YPlaybackRequest(
                container = YContainer.Matroska,
                video =
                    YVideoRequirement(
                        codec = YVideoCodec.Av1,
                        width = width,
                        height = height,
                        frameRate = 24f,
                        hdrType = hdrType,
                    ),
                platformDemuxSupported = false,
                enhancedDemuxSupported = true,
            )
        return YCore2RouteDecision(
            probe =
                YCore2ProbeResult.Success(
                    playbackRequest = request,
                    videoMime = "video/av01",
                    audioMime = null,
                    durationMs = 1_000,
                ),
            plan =
                YPlaybackPlan(
                    route = YPlaybackRoute.SoftwareFallback,
                    demuxPath = YDemuxPath.Enhanced,
                    decodePath = decodePath,
                    renderPath = renderPath,
                    outputHdrType = if (softwareVideoToneMap) YHdrType.Sdr else hdrType,
                    softwareVideoToneMap = softwareVideoToneMap,
                    reason = "test",
                ),
        )
    }
}
