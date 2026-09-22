package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioOutputPath
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.demux.YAudioTrackFormat
import com.yfuse.core2.demux.YDemuxTrack
import com.yfuse.core2.demux.YDemuxTrackType
import com.yfuse.core2.demux.YTrackId
import com.yfuse.core2.strategy.YDecodePath
import com.yfuse.core2.strategy.YDemuxPath
import com.yfuse.core2.strategy.YPlaybackPlan
import com.yfuse.core2.strategy.YRenderPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidEnhancedAudioSelectionTest {
    @Test
    fun hiddenEac3AfterFailedProbeUsesBundledSoftwareAudioWithoutChangingDolbyVideo() {
        val plan = plan(audioPath = YAudioOutputPath.None)
        val original = plan.copy()
        val track = audioTrack(YAudioCodec.Eac3)
        val selected =
            assertNotNull(selectEnhancedAudioTrack(listOf(track), capabilities(), plan, true))

        assertEquals(track.id, selected.track.id)
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
        assertEquals(original, plan)
        assertEquals(YDecodePath.Hardware, plan.decodePath)
        assertEquals(YHdrType.DolbyVision, plan.outputHdrType)
        assertEquals(YPlaybackRoute.NativeEnhanced, plan.route)
    }

    @Test
    fun internalEnhancedRecoveryDoesNotRequirePreflightSoftwareAudioFlag() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(),
                    plan(audioPath = YAudioOutputPath.DecodePcm),
                    softwareDecodeAvailable = true,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
    }

    @Test
    fun unavailableSoftwareExtensionDoesNotInventADecoder() {
        assertNull(
            selectEnhancedAudioTrack(
                listOf(audioTrack(YAudioCodec.Eac3)),
                capabilities(),
                plan(),
                softwareDecodeAvailable = false,
            ),
        )
    }

    @Test
    fun nativePcmDecoderRemainsPreferredWhenAvailable() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(decoders = setOf(YAudioCodec.Eac3)),
                    plan(),
                    softwareDecodeAvailable = true,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun incompletePlanDoesNotSilentlyEnablePassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(passthrough = setOf(YAudioCodec.Eac3)),
                    plan(audioPath = YAudioOutputPath.None),
                    softwareDecodeAvailable = true,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
    }

    @Test
    fun pcmPlanCanUseDecoderWhenDeviceAlsoAdvertisesPassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(setOf(YAudioCodec.Eac3), setOf(YAudioCodec.Eac3)),
                    plan(audioPath = YAudioOutputPath.DecodePcm),
                    softwareDecodeAvailable = false,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun explicitPassthroughPlanRemainsPassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(passthrough = setOf(YAudioCodec.Eac3)),
                    plan(audioPath = YAudioOutputPath.Passthrough),
                    softwareDecodeAvailable = true,
                ),
            )
        assertEquals(YAudioOutputPath.Passthrough, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun softwarePreferenceStillForcesPcmWhenExtensionIsAvailable() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3)),
                    capabilities(setOf(YAudioCodec.Eac3), setOf(YAudioCodec.Eac3)),
                    plan(YAudioOutputPath.Passthrough).copy(softwareAudioDecode = true),
                    softwareDecodeAvailable = true,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
    }

    @Test
    fun videoOnlyOrDataTracksDoNotInventAudio() {
        assertNull(selectEnhancedAudioTrack(emptyList(), capabilities(), plan(), true))
        assertNull(
            selectEnhancedAudioTrack(
                listOf(YDemuxTrack(YTrackId(9), YDemuxTrackType.Data)),
                capabilities(),
                plan(),
                true,
            ),
        )
    }

    @Test
    fun firstPlatformPlayableTrackKeepsPriorityOverSoftwareCandidate() {
        val aac = audioTrack(YAudioCodec.Aac, 2)
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3, 1), aac),
                    capabilities(decoders = setOf(YAudioCodec.Aac)),
                    plan(),
                    true,
                ),
            )
        assertEquals(aac.id, selected.track.id)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun jocCarrierUsesExistingEac3PcmDecoderWithoutClaimingPassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudioTrack(
                    listOf(audioTrack(YAudioCodec.Eac3Joc)),
                    capabilities(decoders = setOf(YAudioCodec.Eac3)),
                    plan(),
                    true,
                ),
            )
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    private fun capabilities(
        decoders: Set<YAudioCodec> = emptySet(),
        passthrough: Set<YAudioCodec> = emptySet(),
    ): YDeviceCapabilities =
        YDeviceCapabilities(
            videoDecoders = emptyList(),
            audioDecoders = decoders,
            audioPassthrough = passthrough,
        )

    private fun audioTrack(
        codec: YAudioCodec,
        id: Int = 1,
    ): YDemuxTrack =
        YDemuxTrack(
            id = YTrackId(id),
            type = YDemuxTrackType.Audio,
            audio = YAudioTrackFormat(codec, "audio/test", channelCount = 6, sampleRate = 48_000),
        )

    private fun plan(audioPath: YAudioOutputPath = YAudioOutputPath.None): YPlaybackPlan =
        YPlaybackPlan(
            route = YPlaybackRoute.NativeEnhanced,
            demuxPath = YDemuxPath.Enhanced,
            decodePath = YDecodePath.Hardware,
            renderPath = YRenderPath.SurfaceDirect,
            outputHdrType = YHdrType.DolbyVision,
            decoderName = "test.dolby.decoder",
            audioPath = audioPath,
            softwareAudioDecode = false,
            reason = "Platform hid audio and bounded enhanced probe failed",
        )
}
