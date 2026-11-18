package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.strategy.YDecodePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDemuxedSoftwareRecoveryPlanTest {
    private val video = YVideoTrackFormat(codec = YVideoCodec.H265, mimeType = "video/hevc")

    @Test
    fun `recovery derives tone mapping from actual demux format without a platform probe`() {
        for (hdr in listOf(YHdrType.Hdr10, YHdrType.Hlg, YHdrType.Hdr10Plus, YHdrType.Sdr)) {
            val plan = softwareRecoveryPlanFromDemux(video.copy(hdrType = hdr), protectedContent = false)

            assertEquals(YPlaybackRoute.SoftwareFallback, plan.route)
            assertEquals(YDecodePath.Software, plan.decodePath)
            assertEquals(hdr, plan.inputHdrType)
            assertEquals(YHdrType.Sdr, plan.outputHdrType)
            assertEquals(hdr != YHdrType.Sdr, plan.softwareVideoToneMap)
        }
    }

    @Test
    fun `only verified and packable HEVC compatible bases use software recovery`() {
        val config =
            YDolbyVisionConfig(
                versionMajor = 1,
                versionMinor = 0,
                profile = 8,
                level = 6,
                rpuPresent = true,
                enhancementLayerPresent = false,
                baseLayerPresent = true,
                baseLayerCompatibilityId = 1,
                metadataCompression = 0,
            )
        val packed = video.copy(samplePacking = YSamplePacking.LengthPrefixed())
        for ((profile, id, hdr) in listOf(
            Triple(7, 6, YHdrType.Hdr10),
            Triple(8, 1, YHdrType.Hdr10),
            Triple(8, 4, YHdrType.Hlg),
            Triple(8, 2, YHdrType.Sdr),
        )) {
            val compatible = config.copy(profile = profile, baseLayerCompatibilityId = id)
            val track = packed.copy(dolbyVisionConfig = compatible)
            val plan = softwareRecoveryPlanFromDemux(track, protectedContent = false)
            assertEquals(YDecodePath.Software, plan.decodePath)
            assertEquals(hdr, plan.inputHdrType)
            assertEquals(YHdrType.Sdr, plan.outputHdrType)
            assertEquals(hdr != YHdrType.Sdr, plan.softwareVideoToneMap)
            assertTrue(plan.usesHdrFallback)
            assertTrue(yCoreSoftwarePlanPassesDolbyGuard(plan, compatible))
            assertFalse(yCoreSoftwarePlanPassesDolbyGuard(plan))
            assertEquals(
                YPlaybackFailureCategory.Drm,
                assertFailsWith<YPlaybackException> {
                    softwareRecoveryPlanFromDemux(
                        track,
                        protectedContent = true,
                    )
                }.category,
            )
        }
        val dolbyTracks =
            listOf(
                video.copy(hdrType = YHdrType.DolbyVision),
                video.copy(mimeType = "video/dolby-vision"),
                video.copy(dolbyVisionConfig = config),
                packed.copy(dolbyVisionConfig = config.copy(profile = 5, baseLayerCompatibilityId = 0)),
                packed.copy(dolbyVisionConfig = config.copy(profile = 10)),
                packed.copy(dolbyVisionConfig = config.copy(baseLayerPresent = false)),
                packed.copy(dolbyVisionConfig = config.copy(baseLayerCompatibilityId = 0)),
                packed.copy(codec = YVideoCodec.H264, dolbyVisionConfig = config),
            )
        for (track in dolbyTracks) {
            val failure =
                assertFailsWith<YPlaybackException> {
                    softwareRecoveryPlanFromDemux(track, protectedContent = false)
                }
            assertEquals(YPlaybackFailureCategory.Decoder, failure.category)
        }
    }

    @Test
    fun `confirmed SDR format does not permit protected software recovery`() {
        val failure =
            assertFailsWith<YPlaybackException> {
                softwareRecoveryPlanFromDemux(video, protectedContent = true)
            }
        assertEquals(YPlaybackFailureCategory.Drm, failure.category)
    }
}
