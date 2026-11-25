package com.yfuse.core2.android

import com.yfuse.core2.capability.YContainer
import com.yfuse.core2.dolby.YDolbyVisionConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCore2DolbyProbePolicyTest {
    @Test
    fun matroska_dolby_probe_runs_when_preloaded_container_is_unknown() {
        assertTrue(shouldProbeMatroskaDolbyVision(YContainer.Matroska, "video/hevc"))
        assertTrue(shouldProbeMatroskaDolbyVision(YContainer.Unknown, "video/hevc"))
        assertTrue(shouldProbeMatroskaDolbyVision(YContainer.Unknown, "video/av01"))
        assertFalse(shouldProbeMatroskaDolbyVision(YContainer.Mp4, "video/hevc"))
        assertFalse(shouldProbeMatroskaDolbyVision(YContainer.Unknown, "audio/eac3"))
    }

    @Test
    fun hevc_rpu_fallback_probe_covers_unknown_and_matroska_without_source_hints() {
        assertEquals(
            4,
            dolbyVisionSampleProbeCount(
                dolbyVisionConfig = null,
                videoMime = "video/hevc",
                container = YContainer.Unknown,
                sourceHintDolbyVision = false,
            ),
        )
        assertEquals(
            4,
            dolbyVisionSampleProbeCount(
                dolbyVisionConfig = null,
                videoMime = "video/hevc",
                container = YContainer.Matroska,
                sourceHintDolbyVision = false,
            ),
        )
        assertEquals(
            24,
            dolbyVisionSampleProbeCount(
                dolbyVisionConfig = null,
                videoMime = "video/hevc",
                container = YContainer.Mp4,
                sourceHintDolbyVision = true,
            ),
        )
        assertEquals(
            0,
            dolbyVisionSampleProbeCount(
                dolbyVisionConfig = null,
                videoMime = "video/hevc",
                container = YContainer.Mp4,
                sourceHintDolbyVision = false,
            ),
        )
        assertEquals(
            0,
            dolbyVisionSampleProbeCount(
                dolbyVisionConfig =
                    YDolbyVisionConfig(
                        versionMajor = 1,
                        versionMinor = 0,
                        profile = 5,
                        level = 6,
                        rpuPresent = true,
                        enhancementLayerPresent = false,
                        baseLayerPresent = true,
                        baseLayerCompatibilityId = 0,
                        metadataCompression = 0,
                    ),
                videoMime = "video/hevc",
                container = YContainer.Unknown,
                sourceHintDolbyVision = true,
            ),
        )
    }
}
