package com.yfuse.feature.player

import com.yfuse.core.playback.DolbyVisionP7OutputEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvDolbyRuntimeEvidenceTest {
    private val profileSeven =
        PlayerMediaVersion(
            id = "p7-fel",
            label = "Dolby Vision P7 FEL",
            detail = "HEVC Dolby Vision Profile 7",
            url = "https://example.invalid/direct",
            transcodeUrl = "",
            fallbackTranscodeUrl = "",
            dolbyVision = true,
            dolbyProfile = 7,
            sourceDolbyRpuPresent = true,
            sourceEnhancementLayerPresent = true,
            sourceBaseLayerPresent = true,
        )

    @Test
    fun p7FelClaimRequiresRenderedEnhancementLayerEvidence() {
        MpvDolbyRuntimeEvidenceRegistry.installProvider {
            MpvDolbyRuntimeEvidence(
                generation = 12L,
                rpuRendered = true,
                felComposed = true,
            )
        }
        try {
            val result =
                profileSeven.dolbyVisionP7Output(
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                    ),
                )

            assertEquals(DolbyVisionP7OutputEvidence.EnhancementLayerComposed, result.evidence)
            assertTrue(result.canClaimFel)
        } finally {
            MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        }
    }

    @Test
    fun p7RpuWithoutEnhancementLayerCannotClaimFel() {
        MpvDolbyRuntimeEvidenceRegistry.installProvider {
            MpvDolbyRuntimeEvidence(
                generation = 13L,
                rpuRendered = true,
                felComposed = false,
            )
        }
        try {
            val result =
                profileSeven.dolbyVisionP7Output(
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        videoReadiness = PlaybackOutputReadiness.Rendering,
                    ),
                )

            assertEquals(DolbyVisionP7OutputEvidence.BaseLayerWithRpu, result.evidence)
            assertFalse(result.canClaimFel)
        } finally {
            MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        }
    }

    @Test
    fun staleRuntimeEvidenceIsIgnoredBeforeMpvRenders() {
        MpvDolbyRuntimeEvidenceRegistry.installProvider {
            MpvDolbyRuntimeEvidence(
                generation = 14L,
                rpuRendered = true,
                felComposed = true,
            )
        }
        try {
            val result =
                profileSeven.dolbyVisionP7Output(
                    PlaybackDiagnostics(
                        engine = "libmpv",
                        videoReadiness = PlaybackOutputReadiness.Waiting,
                    ),
                )

            assertEquals(DolbyVisionP7OutputEvidence.NotMeasured, result.evidence)
            assertFalse(result.canClaimFel)
        } finally {
            MpvDolbyRuntimeEvidenceRegistry.clearProvider()
        }
    }
}
