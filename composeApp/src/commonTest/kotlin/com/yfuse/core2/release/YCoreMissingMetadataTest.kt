package com.yfuse.core2.release

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YCoreMissingMetadataTest {
    @Test
    fun missing_server_metadata_can_reach_the_bounded_local_probe() {
        assertNull(evaluateCore2NativeBaseline(source()))
    }

    @Test
    fun missing_metadata_requires_a_runtime_probe_and_a_supported_transport() {
        assertEquals(
            Core2NativeBaselineBlock.MissingMetadata,
            evaluateCore2NativeBaseline(source().copy(probedBeforeRouting = false)),
        )
        assertEquals(
            Core2NativeBaselineBlock.UnsupportedScheme,
            evaluateCore2NativeBaseline(source().copy(scheme = "ftp")),
        )
    }

    @Test
    fun local_probing_does_not_bypass_known_drm_or_dolby_rejections() {
        assertEquals(Core2NativeBaselineBlock.Drm, evaluateCore2NativeBaseline(source().copy(drm = true)))
        assertEquals(
            Core2NativeBaselineBlock.DolbyVision,
            evaluateCore2NativeBaseline(source().copy(dolbyVision = true)),
        )
    }

    private fun source() =
        Core2NativeBaselineSource(
            hasMetadata = false,
            scheme = "https",
            container = null,
            videoCodec = null,
            serverTranscode = false,
            adaptiveManifest = false,
            disc = false,
            drm = false,
            dolbyVision = false,
            probedBeforeRouting = true,
        )
}
