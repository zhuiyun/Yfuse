package com.yfuse.core.playback

import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.VideoStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionMediaEvidenceMappingTest {
    @Test
    fun media_version_contributes_only_source_layer_facts_to_the_p7_evidence_gate() {
        val version =
            MediaVersion(
                id = "p7-disc",
                name = "UHD Blu-ray",
                container = "m2ts",
                sizeBytes = null,
                bitrateBps = null,
                videoCodec = "hevc",
                videoHeight = 2_160,
                videoRange = "DOVI",
                video =
                    VideoStreamInfo(
                        codec = "hevc",
                        width = 3_840,
                        height = 2_160,
                        bitrateBps = null,
                        bitDepth = 10,
                        frameRate = 23.976,
                        level = null,
                        profile = "Main 10",
                        dolbyProfile = 7,
                        dolbyBaseLayerCompatibility = 1,
                        dolbyRpuPresent = true,
                        dolbyEnhancementLayerPresent = true,
                        dolbyBaseLayerPresent = true,
                    ),
            )

        val evidence = version.dolbyVisionP7ValidationEvidence()
        val result = evaluateDolbyVisionP7Output(evidence)

        assertEquals(7, evidence.profile)
        assertEquals(true, evidence.sourceRpuPresent)
        assertEquals(true, evidence.sourceEnhancementLayerPresent)
        assertEquals(true, evidence.sourceBaseLayerPresent)
        assertFalse(evidence.outputBaseLayerDecoded)
        assertFalse(evidence.outputRpuApplied)
        assertFalse(evidence.outputEnhancementLayerComposed)
        assertEquals(DolbyVisionP7OutputEvidence.NotMeasured, result.evidence)
        assertFalse(result.canClaimFel)
        assertTrue(result.reason.contains("尚无可信"))
    }

    @Test
    fun a_non_dolby_media_version_never_enters_the_profile_seven_validation_lane() {
        val version =
            MediaVersion(
                id = "hdr10",
                name = "HDR10",
                container = "mkv",
                sizeBytes = null,
                bitrateBps = null,
                videoCodec = "hevc",
                videoHeight = 2_160,
                videoRange = "HDR10",
                video =
                    VideoStreamInfo(
                        codec = "hevc",
                        width = 3_840,
                        height = 2_160,
                        bitrateBps = null,
                        bitDepth = 10,
                        frameRate = 23.976,
                        level = null,
                        profile = "Main 10",
                    ),
            )

        val result = evaluateDolbyVisionP7Output(version.dolbyVisionP7ValidationEvidence())

        assertEquals(DolbyVisionP7OutputEvidence.NotApplicable, result.evidence)
        assertFalse(result.canClaimFel)
    }
}
