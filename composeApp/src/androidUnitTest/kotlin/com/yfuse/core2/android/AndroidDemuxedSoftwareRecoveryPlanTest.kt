package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.demux.YVideoTrackFormat
import com.yfuse.core2.dolby.YDolbyVisionConfig
import com.yfuse.core2.strategy.YDecodePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `any demuxed Dolby identity prevents ordinary software recovery`() {
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
        val dolbyTracks =
            listOf(
                video.copy(hdrType = YHdrType.DolbyVision),
                video.copy(mimeType = "video/dolby-vision"),
                video.copy(dolbyVisionConfig = config),
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
