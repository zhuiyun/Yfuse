package com.yfuse.core2.hdr

import com.yfuse.core2.capability.YDeviceCapabilities
import com.yfuse.core2.capability.YHdrType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class YHdrRouterTest {
    @Test
    fun `HDR10 plus stays native only when the display declares it`() {
        val descriptor =
            YHdrPlaybackDescriptor(
                type = YHdrType.Hdr10Plus,
                primaries = YColorPrimaries.Bt2020,
                transfer = YColorTransfer.Pq,
                hdr10PlusMetadata = YHdr10PlusMetadata(byteArrayOf(0x01, 0x02)),
            )
        val capabilities =
            YDeviceCapabilities(
                videoDecoders = emptyList(),
                displayHdrTypes = setOf(YHdrType.Sdr, YHdrType.Hdr10Plus),
            )

        val route = assertIs<YHdrRouteDecision.Native>(YHdrRouter.decide(descriptor, capabilities))
        assertEquals(YHdrType.Hdr10Plus, route.outputType)
    }

    @Test
    fun `unsupported HLG requires the GPU tone-map route`() {
        val descriptor =
            YHdrPlaybackDescriptor(
                type = YHdrType.Hlg,
                primaries = YColorPrimaries.Bt2020,
                transfer = YColorTransfer.Hlg,
            )

        val route =
            assertIs<YHdrRouteDecision.GpuToneMap>(
                YHdrRouter.decide(descriptor, YDeviceCapabilities.conservative()),
            )
        assertEquals(YHdrType.Sdr, route.outputType)
    }
}
