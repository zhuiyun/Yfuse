package com.yfuse.core2.android

import com.yfuse.core2.release.Core2NativeBaselineBlock
import com.yfuse.core2.release.Core2NativeBaselineSource
import com.yfuse.core2.release.evaluateCore2NativeBaseline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Core2NativeBaselineTest {
    @Test
    fun progressive_mp4_and_mkv_with_avc_or_hevc_enter_the_native_lane() {
        assertNull(evaluateCore2NativeBaseline(source(container = "MP4", codec = "avc1.640028")))
        assertNull(evaluateCore2NativeBaseline(source(container = "MKV", codec = "HEVC")))
        assertNull(evaluateCore2NativeBaseline(source(container = "video/mp4", codec = "H.264")))
    }

    @Test
    fun adaptive_drm_dolby_and_disc_sources_are_fail_closed() {
        assertEquals(
            Core2NativeBaselineBlock.AdaptiveManifest,
            evaluateCore2NativeBaseline(source(adaptive = true)),
        )
        assertEquals(
            Core2NativeBaselineBlock.Drm,
            evaluateCore2NativeBaseline(source(drm = true)),
        )
        assertEquals(
            Core2NativeBaselineBlock.DolbyVision,
            evaluateCore2NativeBaseline(source(dolbyVision = true)),
        )
        assertEquals(
            Core2NativeBaselineBlock.Disc,
            evaluateCore2NativeBaseline(source(disc = true)),
        )
    }

    @Test
    fun implemented_hls_transport_lane_bypasses_progressive_container_gate() {
        assertNull(
            evaluateCore2NativeBaseline(
                source(
                    container = "hls",
                    codec = "h264",
                    adaptive = true,
                    adaptiveSupported = true,
                ),
            ),
        )
    }

    @Test
    fun unsupported_or_unknown_metadata_cannot_silently_use_a_legacy_engine() {
        assertEquals(
            Core2NativeBaselineBlock.MissingMetadata,
            evaluateCore2NativeBaseline(source(hasMetadata = false)),
        )
        assertEquals(
            Core2NativeBaselineBlock.UnsupportedContainer,
            evaluateCore2NativeBaseline(source(container = "AVI")),
        )
        assertEquals(
            Core2NativeBaselineBlock.UnsupportedVideoCodec,
            evaluateCore2NativeBaseline(source(codec = "AV1")),
        )
        assertEquals(
            Core2NativeBaselineBlock.UnsupportedScheme,
            evaluateCore2NativeBaseline(source(scheme = "smb")),
        )
    }

    private fun source(
        hasMetadata: Boolean = true,
        scheme: String = "https",
        container: String? = "mkv",
        codec: String? = "hevc",
        serverTranscode: Boolean = false,
        adaptive: Boolean = false,
        adaptiveSupported: Boolean = false,
        disc: Boolean = false,
        drm: Boolean = false,
        dolbyVision: Boolean = false,
        externalSubtitleSupported: Boolean = true,
    ) = Core2NativeBaselineSource(
        hasMetadata = hasMetadata,
        scheme = scheme,
        container = container,
        videoCodec = codec,
        serverTranscode = serverTranscode,
        adaptiveManifest = adaptive,
        adaptiveManifestSupported = adaptiveSupported,
        disc = disc,
        drm = drm,
        dolbyVision = dolbyVision,
        externalSubtitleSupported = externalSubtitleSupported,
    )
}
