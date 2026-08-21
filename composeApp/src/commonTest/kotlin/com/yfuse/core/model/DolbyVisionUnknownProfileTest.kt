package com.yfuse.core.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionUnknownProfileTest {
    @Test
    fun unknown_dolby_profile_without_base_layer_evidence_requires_dolby_decoder() {
        val subject =
            MediaVersion(
                id = "dv-unknown",
                name = "DV",
                container = "mkv",
                sizeBytes = null,
                bitrateBps = null,
                videoCodec = "hevc",
                videoHeight = 2160,
                videoRange = "Dolby Vision",
            )

        assertTrue(subject.isDolbyVision)
        assertTrue(subject.needsDolbyCapableDecoder)
    }

    @Test
    fun unknown_dolby_profile_with_explicit_compatible_base_layer_can_use_base_decoder() {
        val subject =
            MediaVersion(
                id = "dv-compatible",
                name = "DV",
                container = "mkv",
                sizeBytes = null,
                bitrateBps = null,
                videoCodec = "hevc",
                videoHeight = 2160,
                videoRange = "Dolby Vision",
                video =
                    VideoStreamInfo(
                        dolbyBaseLayerCompatibility = 1,
                        dolbyBaseLayerPresent = true,
                    ),
            )

        assertTrue(subject.isDolbyVision)
        assertFalse(subject.needsDolbyCapableDecoder)
    }
}
