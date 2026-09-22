package com.yfuse.core.data.dto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DolbyVisionLayerMappingTest {
    @Test
    fun jellyfin_dolby_layer_flags_survive_media_source_mapping() {
        val version =
            MediaSourceDto(
                Id = "uhd-source",
                Container = "m2ts",
                MediaStreams =
                    listOf(
                        MediaStreamDto(
                            Type = "Video",
                            Codec = "hevc",
                            VideoRange = "DOVI",
                            DvProfile = 7,
                            RpuPresentFlag = 1,
                            ElPresentFlag = 1,
                            BlPresentFlag = 1,
                            DvBlSignalCompatibilityId = 1,
                        ),
                    ),
            ).toMediaVersion(fallbackId = "movie", ordinal = 0)

        assertTrue(version.isDolbyVision)
        assertEquals(7, version.dolbyProfile)
        assertTrue(version.hasDolbyVisionRpu)
        assertTrue(version.hasDolbyVisionEnhancementLayer)
        assertTrue(version.requiresDolbyVisionEnhancementValidation)
        assertEquals("Dolby Vision P7 · 双层", version.rangeLabel)
    }
}
