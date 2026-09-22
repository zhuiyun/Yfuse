package com.yfuse.core2.strategy

import com.yfuse.core2.capability.YAudioCodec
import com.yfuse.core2.capability.YAudioRequirement
import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import com.yfuse.core2.capability.YVideoCodec
import com.yfuse.core2.capability.YVideoDecoderCapability
import com.yfuse.core2.capability.YVideoRequirement
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YTunnelPolicyTest {
    private val decoder =
        YVideoDecoderCapability(
            name = "c2.vendor.hevc.decoder",
            codec = YVideoCodec.H265,
            hdrTypes = setOf(YHdrType.Sdr),
            maxWidth = 3840,
            maxHeight = 2160,
            maxFrameRate = 60.0,
            maxBitDepth = 10,
            tunneledPlayback = true,
        )
    private val capabilities =
        YDeviceCapabilities(
            videoDecoders = listOf(decoder),
            audioDecoders = setOf(YAudioCodec.Aac),
            supportsSurfaceDirect = true,
            supportsTunnel = true,
        )

    @Test
    fun `tunnel requires video audio surface and native audio`() {
        val request = request(audio = YAudioRequirement(YAudioCodec.Aac))
        assertTrue(canUseNativeTunnel(request, capabilities, decoder))
    }

    @Test
    fun `video-only media never enters audio-clocked tunnel`() {
        assertFalse(canUseNativeTunnel(request(audio = null), capabilities, decoder))
    }

    @Test
    fun `software audio fallback disqualifies tunnel`() {
        val request = request(audio = YAudioRequirement(YAudioCodec.DtsHd, channelCount = 8))
        assertFalse(canUseNativeTunnel(request, capabilities, decoder))
    }

    @Test
    fun `enhanced demux stays outside Phase 4 tunnel route`() {
        val request =
            request(audio = YAudioRequirement(YAudioCodec.Aac)).copy(
                platformDemuxSupported = false,
                enhancedDemuxSupported = true,
            )
        assertFalse(canUseNativeTunnel(request, capabilities, decoder))
    }

    private fun request(audio: YAudioRequirement?): YPlaybackRequest =
        YPlaybackRequest(
            container = YContainer.Mp4,
            video = YVideoRequirement(codec = YVideoCodec.H265),
            audio = audio,
            platformDemuxSupported = true,
            preferTunnel = true,
        )
}
