package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DolbyVisionEnhancementValidationTest {
    @Test
    fun profile_seven_el_presence_alone_is_not_fel_output_evidence() {
        val result =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 7,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = true,
                    sourceBaseLayerPresent = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.NotMeasured, result.evidence)
        assertFalse(result.canClaimFel)
        assertTrue(result.reason.contains("尚无可信"))
    }

    @Test
    fun base_layer_and_rpu_output_still_does_not_prove_fel_composition() {
        val result =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 7,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = true,
                    sourceBaseLayerPresent = true,
                    outputBaseLayerDecoded = true,
                    outputRpuApplied = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.BaseLayerWithRpu, result.evidence)
        assertFalse(result.canClaimFel)
    }

    @Test
    fun only_explicit_enhancement_composition_evidence_allows_an_fel_claim() {
        val result =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 7,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = true,
                    sourceBaseLayerPresent = true,
                    outputBaseLayerDecoded = true,
                    outputRpuApplied = true,
                    outputEnhancementLayerComposed = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.EnhancementLayerComposed, result.evidence)
        assertTrue(result.canClaimFel)
    }

    @Test
    fun contradictory_missing_base_layer_metadata_never_promotes_a_composition_claim() {
        val result =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 7,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = true,
                    sourceBaseLayerPresent = false,
                    outputEnhancementLayerComposed = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.NotMeasured, result.evidence)
        assertFalse(result.canClaimFel)
        assertTrue(result.reason.contains("base layer 缺失"))
    }

    @Test
    fun profile_eight_or_profile_seven_without_el_is_not_an_fel_validation_case() {
        val p8 =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 8,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = false,
                    sourceBaseLayerPresent = true,
                ),
            )
        val p7NoEl =
            evaluateDolbyVisionP7Output(
                DolbyVisionP7ValidationEvidence(
                    profile = 7,
                    sourceRpuPresent = true,
                    sourceEnhancementLayerPresent = false,
                    sourceBaseLayerPresent = true,
                ),
            )

        assertEquals(DolbyVisionP7OutputEvidence.NotApplicable, p8.evidence)
        assertEquals(DolbyVisionP7OutputEvidence.NotApplicable, p7NoEl.evidence)
        assertFalse(p8.canClaimFel)
        assertFalse(p7NoEl.canClaimFel)
    }
}
