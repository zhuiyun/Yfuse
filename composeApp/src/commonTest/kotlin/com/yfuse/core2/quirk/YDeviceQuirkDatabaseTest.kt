package com.yfuse.core2.quirk

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import com.yfuse.core2.strategy.YPlaybackRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YDeviceQuirkDatabaseTest {
    @Test
    fun `applies matching decoder rule without affecting other devices`() {
        val database =
            YDeviceQuirkDatabase(
                listOf(
                    YDeviceQuirkRule(
                        id = "vendor-api30-broken-dv",
                        manufacturer = YTextMatch.Exact("Vendor"),
                        maximumApi = 30,
                        decoder = YTextMatch.Prefix("c2.vendor.dv"),
                        container = YContainer.Matroska,
                        videoCodec = YVideoCodec.H265,
                        hdrType = YHdrType.DolbyVision,
                        dolbyVisionProfile = 8,
                        minimumWidth = 3_840,
                        minimumHeight = 2_160,
                        actions = setOf(YDeviceQuirkAction.DisableDecoder),
                    ),
                ),
            )

        val affected = database.adjust(identity("Vendor", 30), request(), capabilities())
        val unaffected = database.adjust(identity("Other", 30), request(), capabilities())

        assertEquals(listOf("c2.vendor.hevc"), affected.capabilities.videoDecoders.map { it.name })
        assertEquals(2, unaffected.capabilities.videoDecoders.size)
        assertEquals(setOf("vendor-api30-broken-dv"), affected.matchedRuleIds)
    }

    @Test
    fun `global rule forces enhanced demux and disables passthrough and tunnel`() {
        val database =
            YDeviceQuirkDatabase(
                listOf(
                    YDeviceQuirkRule(
                        id = "model-route-workaround",
                        model = YTextMatch.Prefix("Box X"),
                        actions =
                            setOf(
                                YDeviceQuirkAction.ForceEnhancedDemux,
                                YDeviceQuirkAction.DisableAudioPassthrough,
                                YDeviceQuirkAction.DisableTunnel,
                            ),
                    ),
                ),
            )

        val adjusted = database.adjust(identity("Vendor", 36), request(), capabilities())

        assertFalse(adjusted.request.platformDemuxSupported)
        assertFalse(adjusted.request.allowAudioPassthrough)
        assertFalse(adjusted.request.preferTunnel)
        assertFalse(adjusted.capabilities.supportsTunnel)
        assertTrue(adjusted.capabilities.audioPassthrough.isEmpty())
        assertTrue(adjusted.capabilities.videoDecoders.none { it.tunneledPlayback })
    }

    private fun identity(
        manufacturer: String,
        api: Int,
    ) = YDeviceIdentity(manufacturer, model = "Box X Pro", soc = "soc", androidApi = api)

    private fun request() =
        YPlaybackRequest(
            container = YContainer.Matroska,
            video =
                YVideoRequirement(
                    codec = YVideoCodec.H265,
                    width = 3_840,
                    height = 2_160,
                    hdrType = YHdrType.DolbyVision,
                    dolbyVisionProfile = 8,
                ),
            platformDemuxSupported = true,
            enhancedDemuxSupported = true,
        )

    private fun capabilities() =
        YDeviceCapabilities(
            videoDecoders =
                listOf(
                    decoder("c2.vendor.dv", setOf(YHdrType.Sdr, YHdrType.DolbyVision)),
                    decoder("c2.vendor.hevc", setOf(YHdrType.Sdr, YHdrType.DolbyVision)),
                ),
            audioDecoders = setOf(YAudioCodec.Aac),
            audioPassthrough = setOf(YAudioCodec.TrueHd),
            displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.DolbyVision),
            supportsTunnel = true,
        )

    private fun decoder(
        name: String,
        hdr: Set<YHdrType>,
    ) = YVideoDecoderCapability(
        name = name,
        codec = YVideoCodec.H265,
        hdrTypes = hdr,
        dolbyVisionProfiles = setOf(8),
        tunneledPlayback = true,
    )
}
