package com.yfuse.core2.dolby

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YDolbyVisionFelEvidenceProviderTest {
    private val profile7 =
        YDolbyVisionConfig(
            versionMajor = 1,
            versionMinor = 0,
            profile = 7,
            level = 6,
            rpuPresent = true,
            enhancementLayerPresent = true,
            baseLayerPresent = true,
            baseLayerCompatibilityId = 1,
            metadataCompression = 0,
        )

    @Test
    fun `default provider never promotes delivered EL to FEL composition`() {
        assertFalse(
            verifyDolbyVisionFelComposition(
                completeRequest(),
                FailClosedYDolbyVisionFelEvidenceProvider,
            ),
        )
    }

    @Test
    fun `trusted proof still requires frame RPU and enhancement layer observations`() {
        val trusted = YDolbyVisionFelEvidenceProvider { true }
        assertTrue(verifyDolbyVisionFelComposition(completeRequest(), trusted))
        assertFalse(
            verifyDolbyVisionFelComposition(
                completeRequest().copy(enhancementLayerAccessUnitObserved = false),
                trusted,
            ),
        )
    }

    private fun completeRequest() =
        YDolbyVisionFelCompositionRequest(
            config = profile7,
            decoderName = "vendor.dv.decoder",
            renderedFrameObserved = true,
            rpuAccessUnitObserved = true,
            enhancementLayerAccessUnitObserved = true,
        )
}
