package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEnhancedDolbySamplesTest {
    @Test
    fun `av1 dolby vision profile 10 point 1 resolves compatible av1 hdr10 base track and normalizes obus`() {
        val p10Config =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 10,
                level = 9,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 1,
                metadataCompression = 0,
            )
        val source =
            YVideoTrackFormat(
                codec = YVideoCodec.Av1,
                mimeType = "video/dolby-vision",
                width = 3840,
                height = 2160,
                frameRate = 24f,
                bitDepth = 10,
                hdrType = YHdrType.DolbyVision,
                dolbyVisionConfig = p10Config,
            )
        val plan =
            YPlaybackPlan(
                route = YPlaybackRoute.NativeEnhanced,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.SurfaceDirect,
                inputHdrType = YHdrType.Hdr10,
                outputHdrType = YHdrType.Hdr10,
                usesHdrFallback = true,
                reason = "compatible base",
            )

        assertTrue(plan.extractsDolbyBaseLayerFor(source))
        val effective = resolveEffectiveEnhancedVideoTrack(source, plan)
        assertEquals("video/av01", effective.mimeType)
        assertEquals(YHdrType.Hdr10, effective.hdrType)
        assertNull(effective.dolbyVisionConfig)

        // Temporal delimiter OBU with explicit size 0: header 0x12 (type=2, has_size=1), size 0x00.
        val obuSample = byteArrayOf(0x12, 0x00)
        val transformed = transformEnhancedVideoSample(obuSample, source, plan, softwareVideoActive = false)
        assertContentEquals(obuSample, transformed)
    }

    @Test
    fun `hdr10 plus fallback to hdr10 does not masquerade as dolby base extraction`() {
        val source =
            YVideoTrackFormat(
                codec = YVideoCodec.H265,
                mimeType = "video/hevc",
                width = 3840,
                height = 2160,
                frameRate = 24f,
                bitDepth = 10,
                hdrType = YHdrType.Hdr10Plus,
                samplePacking = YSamplePacking.AnnexB,
            )
        val plan =
            YPlaybackPlan(
                route = YPlaybackRoute.NativeEnhanced,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.SurfaceDirect,
                inputHdrType = YHdrType.Hdr10,
                outputHdrType = YHdrType.Hdr10,
                usesHdrFallback = true,
                reason = "hdr10+ fallback to hdr10",
            )

        assertFalse(plan.extractsDolbyBaseLayerFor(source))
        val effective = resolveEffectiveEnhancedVideoTrack(source, plan)
        assertEquals("video/hevc", effective.mimeType)
        assertEquals(YHdrType.Hdr10, effective.hdrType)
    }

    @Test
    fun `profile 7 to 8 point 1 plan rewrites track configuration to single-layer profile 8`() {
        val p7Config =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 7,
                level = 6,
                rpuPresent = true,
                enhancementLayerPresent = true,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 6,
                metadataCompression = 0,
            )
        val source =
            YVideoTrackFormat(
                codec = YVideoCodec.H265,
                mimeType = "video/dolby-vision",
                width = 3840,
                height = 2160,
                frameRate = 23.976f,
                bitDepth = 10,
                hdrType = YHdrType.DolbyVision,
                samplePacking = YSamplePacking.LengthPrefixed(4),
                dolbyVisionConfig = p7Config,
            )
        val plan =
            YPlaybackPlan(
                route = YPlaybackRoute.NativeEnhanced,
                demuxPath = YDemuxPath.Enhanced,
                decodePath = YDecodePath.Hardware,
                renderPath = YRenderPath.SurfaceDirect,
                inputHdrType = YHdrType.DolbyVision,
                outputHdrType = YHdrType.DolbyVision,
                convertsDolbyProfile7To8 = true,
                reason = "convert p7 to p8.1",
            )

        val effective = resolveEffectiveEnhancedVideoTrack(source, plan)
        assertEquals("video/dolby-vision", effective.mimeType)
        assertEquals(YSamplePacking.AnnexB, effective.samplePacking)
        val rewritten = assertNotNull(effective.dolbyVisionConfig)
        assertEquals(8, rewritten.profile)
        assertEquals(1, rewritten.baseLayerCompatibilityId)
        assertFalse(rewritten.enhancementLayerPresent)
    }
}
