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

class YEnhancedAudioSelectionTest {
    private val provisionalPlan =
        YPlaybackPlan(
            route = YPlaybackRoute.NativeEnhanced,
            demuxPath = YDemuxPath.Enhanced,
            decodePath = YDecodePath.Hardware,
            renderPath = YRenderPath.SurfaceDirect,
            outputHdrType = YHdrType.DolbyVision,
            audioPath = YAudioOutputPath.None,
            softwareAudioDecode = false,
            reason = "Platform hid EAC3 and the enhanced truth probe failed",
        )
    private val eac3 = track(1, YAudioCodec.Eac3)

    @Test
    fun actualEac3TrackCanUseSoftwareAudioWithTheDolbyHardwareVideoPlan() {
        val selected =
            assertNotNull(
                selectEnhancedAudio(
                    listOf(eac3),
                    provisionalPlan,
                    capabilities(),
                    false,
                    true,
                ),
            )

        assertEquals(eac3, selected.track)
        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
        assertEquals(YDecodePath.Hardware, provisionalPlan.decodePath)
        assertEquals(YHdrType.DolbyVision, provisionalPlan.outputHdrType)
    }

    @Test
    fun pcmPreferenceUsesHardwareDecoderEvenWhenDeviceAlsoAdvertisesPassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudio(
                    listOf(eac3),
                    provisionalPlan.copy(audioPath = YAudioOutputPath.DecodePcm),
                    capabilities(decoders = setOf(YAudioCodec.Eac3), passthrough = setOf(YAudioCodec.Eac3)),
                    true,
                    true,
                ),
            )

        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun unknownProbeAudioDoesNotOverrideDisabledPassthrough() {
        val selected =
            assertNotNull(
                selectEnhancedAudio(
                    listOf(eac3),
                    provisionalPlan,
                    capabilities(passthrough = setOf(YAudioCodec.Eac3)),
                    false,
                    true,
                ),
            )

        assertEquals(YAudioOutputPath.DecodePcm, selected.outputPath)
        assertTrue(selected.preferSoftware)
    }

    @Test
    fun enabledPassthroughIsRetainedWhenTheActualTrackSupportsIt() {
        val selected =
            assertNotNull(
                selectEnhancedAudio(
                    listOf(eac3),
                    provisionalPlan.copy(audioPath = YAudioOutputPath.Passthrough),
                    capabilities(passthrough = setOf(YAudioCodec.Eac3)),
                    true,
                    true,
                ),
            )

        assertEquals(YAudioOutputPath.Passthrough, selected.outputPath)
        assertFalse(selected.preferSoftware)
    }

    @Test
    fun missingSoftwareExtensionDoesNotInventADecoderOrDropRequiredAudio() {
        assertNull(selectEnhancedAudio(listOf(eac3), provisionalPlan, capabilities(), false, false))
    }

    @Test
    fun videoOnlyMediaDoesNotInventAnAudioTrack() {
        assertNull(selectEnhancedAudio(emptyList(), provisionalPlan, capabilities(), false, true))
    }

    @Test
    fun playableNativeTrackIsPreferredBeforeSoftwareOnlyTrack() {
        val aac = track(2, YAudioCodec.Aac)
        val selected =
            assertNotNull(
                selectEnhancedAudio(
                    listOf(eac3, aac),
                    provisionalPlan,
                    capabilities(decoders = setOf(YAudioCodec.Aac)),
                    false,
                    true,
                ),
            )

        assertEquals(aac, selected.track)
        assertFalse(selected.preferSoftware)
    }

    // Keep the original regression scenarios against the consolidated production selector.
    private fun selectEnhancedAudio(
        tracks: List<YDemuxTrack>,
        plan: YPlaybackPlan,
        capabilities: YDeviceCapabilities,
        allowPassthrough: Boolean,
        softwareAvailable: Boolean,
    ) = selectEnhancedAudioTrack(
        tracks = tracks,
        capabilities = if (allowPassthrough) capabilities else capabilities.copy(audioPassthrough = emptySet()),
        plan = plan,
        softwareDecodeAvailable = softwareAvailable,
    )

    private fun capabilities(
        decoders: Set<YAudioCodec> = emptySet(),
        passthrough: Set<YAudioCodec> = emptySet(),
    ) = YDeviceCapabilities(videoDecoders = emptyList(), audioDecoders = decoders, audioPassthrough = passthrough)

    private fun track(
        id: Int,
        codec: YAudioCodec,
    ) = YDemuxTrack(
        id = YTrackId(id),
        type = YDemuxTrackType.Audio,
        audio = YAudioTrackFormat(codec, "audio/${codec.name.lowercase()}", 6, 48_000),
    )
}
