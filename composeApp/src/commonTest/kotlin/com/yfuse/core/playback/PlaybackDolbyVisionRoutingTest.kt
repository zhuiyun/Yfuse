package com.yfuse.core.playback

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackDolbyVisionRoutingTest {
    @Test
    fun conservative_runtime_never_infers_dolby_processing_from_display_or_profile() {
        listOf(profileSeven(), profileEightOne()).forEach { source ->
            val route =
                playbackDolbyVisionRoute(
                    source,
                    capabilities(true, true),
                    PlaybackDolbyVisionRuntimeCapabilities.conservative(),
                )
            assertEquals(PlaybackDolbyVisionPath.Unverified, route.path)
            assertEquals(PlayerEngine.YCore, route.engine)
            assertFalse(route.stripDolbyVisionToBaseLayer)
        }
    }

    @Test
    fun native_output_requires_both_current_runtime_proof_and_compatible_display() {
        val proof = PlaybackDolbyVisionRuntimeCapabilities(verifiedNativeDolbyVision = true)
        assertEquals(
            PlaybackDolbyVisionPath.MediaCodecNative,
            playbackDolbyVisionRoute(profileEightOne(), capabilities(true, true), proof).path,
        )
        assertEquals(
            PlaybackDolbyVisionPath.Unverified,
            playbackDolbyVisionRoute(profileEightOne(), capabilities(false, true), proof).path,
        )
    }

    @Test
    fun native_dolby_proof_does_not_imply_profile_seven_enhancement_layer_composition() {
        val route =
            playbackDolbyVisionRoute(
                profileSeven(),
                capabilities(true, true),
                PlaybackDolbyVisionRuntimeCapabilities(verifiedNativeDolbyVision = true),
            )
        assertEquals(PlaybackDolbyVisionPath.Unverified, route.path)
    }

    @Test
    fun hdr10_base_requires_positive_runtime_conversion_and_compatible_base_and_display() {
        val proof = PlaybackDolbyVisionRuntimeCapabilities(verifiedHdr10BaseLayer = true)
        listOf(profileSeven(), profileEightOne()).forEach { source ->
            val route = playbackDolbyVisionRoute(source, capabilities(false, true), proof)
            assertEquals(PlaybackDolbyVisionPath.Hdr10BaseLayer, route.path)
            assertTrue(route.stripDolbyVisionToBaseLayer)
            assertEquals(
                PlaybackDolbyVisionPath.Unverified,
                playbackDolbyVisionRoute(source, capabilities(false, false), proof).path,
            )
            assertEquals(
                PlaybackDolbyVisionPath.Unverified,
                playbackDolbyVisionRoute(
                    source.copy(dolbyBaseLayerPresent = false),
                    capabilities(false, true),
                    proof,
                ).path,
            )
        }
        assertEquals(
            PlaybackDolbyVisionPath.Unverified,
            playbackDolbyVisionRoute(
                profileEightOne().copy(dolbyBaseLayerCompatibilityId = null),
                capabilities(false, true),
                proof,
            ).path,
        )
    }

    private fun profileSeven() =
        PlaybackSourceRequirements(
            dolbyVision = true,
            needsDolbyDecoder = false,
            dynamicRange = "Dolby Vision P7",
            dolbyVisionProfile = 7,
            dolbyRpuPresent = true,
            dolbyEnhancementLayerPresent = true,
            dolbyBaseLayerPresent = true,
            dolbyBaseLayerCompatibilityId = 0,
            videoCodec = PlaybackVideoCodec.Hevc,
            bitDepth = 10,
        )

    private fun profileEightOne() =
        PlaybackSourceRequirements(
            dolbyVision = true,
            needsDolbyDecoder = false,
            dynamicRange = "Dolby Vision P8.1",
            dolbyVisionProfile = 8,
            dolbyRpuPresent = true,
            dolbyEnhancementLayerPresent = false,
            dolbyBaseLayerPresent = true,
            dolbyBaseLayerCompatibilityId = 1,
            videoCodec = PlaybackVideoCodec.Hevc,
            bitDepth = 10,
        )

    private fun capabilities(
        dolby: Boolean,
        hdr10: Boolean,
    ) = PlaybackDeviceCapabilities(
        hdrFormats =
            buildSet {
                if (dolby) add(PlaybackHdrFormat.DolbyVision)
                if (hdr10) add(PlaybackHdrFormat.Hdr10)
            },
        videoDecoders =
            buildSet {
                add(PlaybackVideoCodec.Hevc)
                if (dolby) add(PlaybackVideoCodec.DolbyVision)
            },
        hdrDecoders =
            buildMap {
                put(
                    PlaybackVideoCodec.Hevc,
                    setOf(PlaybackHdrFormat.Hdr10).takeIf { hdr10 }.orEmpty(),
                )
                if (dolby) {
                    put(
                        PlaybackVideoCodec.DolbyVision,
                        setOf(PlaybackHdrFormat.DolbyVision),
                    )
                }
            },
        audioDecoders = setOf(PlaybackAudioCodec.Aac),
        directAudioFormats = emptySet(),
        dolbyVisionCodecProfiles = emptySet(),
        dolbyVisionBaseCodecs =
            setOf(PlaybackVideoCodec.Hevc).takeIf { dolby }.orEmpty(),
        audioRoutes = setOf(PlaybackAudioRoute.BuiltIn),
        maxAudioChannels = 2,
    )
}
