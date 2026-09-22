package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.api.YMediaSourceHints
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackRequest
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.PlayerMediaVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCore2DolbyTrialGuardTest {
    @Test
    fun unknown_dolby_profile_enters_local_truth_probe() {
        val queue =
            listOf(
                item(
                    dolbyProfile = null,
                    needsDolbyDecoder = false,
                ),
            )

        assertTrue(queue.canUseCore2Trial(startIndex = 0))
        assertNull(queue.core2NativeBaselineBlockReason(startIndex = 0))
    }

    @Test
    fun known_unsupported_dolby_profile_remains_blocked() {
        assertFalse(
            listOf(
                item(
                    dolbyProfile = 6,
                    needsDolbyDecoder = true,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun platform_dolby_without_configuration_requires_enhanced_truth_probe() {
        val platform =
            YCore2ProbeResult.Success(
                playbackRequest =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                width = 3840,
                                height = 2160,
                                frameRate = 24f,
                                bitDepth = 10,
                                hdrType = YHdrType.DolbyVision,
                                dolbyVisionProfile = null,
                            ),
                        platformDemuxSupported = true,
                        enhancedDemuxSupported = true,
                    ),
                videoMime = "video/dolby-vision",
                audioMime = null,
                durationMs = 60_000L,
            )

        assertTrue(platform.requiresEnhancedTruthProbe())
    }

    @Test
    fun server_dolby_hint_prevents_generic_hevc_platform_routing() {
        val genericPlatform =
            YCore2ProbeResult.Success(
                playbackRequest =
                    YPlaybackRequest(
                        container = YContainer.Matroska,
                        video =
                            YVideoRequirement(
                                codec = YVideoCodec.H265,
                                width = 3840,
                                height = 2160,
                                frameRate = 24f,
                                bitDepth = 10,
                                hdrType = YHdrType.Sdr,
                            ),
                        platformDemuxSupported = true,
                        enhancedDemuxSupported = true,
                    ),
                videoMime = "video/hevc",
                audioMime = null,
                durationMs = 60_000L,
            )
        val item =
            YMediaItem(
                id = "remote-dv",
                uri = "https://media.example.test/direct-stream/opaque-id",
                sourceHints = YMediaSourceHints(dolbyVision = true),
            )

        assertTrue(genericPlatform.requiresEnhancedTruthProbe(item))

        val confirmedProfile5 =
            genericPlatform.withConfirmedDolbyVisionSourceHint(
                item.copy(
                    sourceHints =
                        YMediaSourceHints(
                            videoCodec = "hevc",
                            dolbyVision = true,
                            dolbyVisionProfile = 5,
                        ),
                ),
            )

        assertTrue(confirmedProfile5.playbackRequest.video.hdrType == YHdrType.DolbyVision)
        assertTrue(confirmedProfile5.playbackRequest.video.dolbyVisionProfile == 5)
        assertTrue(confirmedProfile5.videoMime == "video/dolby-vision")
        assertTrue(confirmedProfile5.dolbyVisionConfig?.profile == 5)
    }

    @Test
    fun dolby_profile_5_enters_strict_runtime_routing() {
        assertTrue(
            listOf(
                item(
                    dolbyProfile = 5,
                    needsDolbyDecoder = true,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun dolby_profile_7_enters_layer_aware_runtime_routing() {
        assertTrue(
            listOf(
                item(
                    dolbyProfile = 7,
                    needsDolbyDecoder = true,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun dolby_profile_8_with_compatible_base_layer_remains_trial_eligible() {
        assertTrue(
            listOf(
                item(
                    dolbyProfile = 8,
                    needsDolbyDecoder = false,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    @Test
    fun dolby_profile_10_enters_av1_runtime_routing() {
        assertTrue(
            listOf(
                item(
                    dolbyProfile = 10,
                    needsDolbyDecoder = true,
                ),
            ).canUseCore2Trial(startIndex = 0),
        )
    }

    private fun item(
        dolbyProfile: Int?,
        needsDolbyDecoder: Boolean,
    ): PlayerMediaItem {
        val url = "https://media.example.test/movie.mkv"
        val version =
            PlayerMediaVersion(
                id = "dv-${dolbyProfile ?: "unknown"}",
                label = "Dolby Vision",
                detail = "DV",
                url = url,
                transcodeUrl = "",
                fallbackTranscodeUrl = "",
                container = "mkv",
                sourceVideoCodec = if (dolbyProfile == 10) "av1" else "hevc",
                dolbyVision = true,
                dolbyProfile = dolbyProfile,
                needsDolbyDecoder = needsDolbyDecoder,
            )
        return PlayerMediaItem(
            id = "movie",
            url = url,
            transcodeUrl = "",
            title = "Movie",
            versions = listOf(version),
            versionId = version.id,
        )
    }
}
