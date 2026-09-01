package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YDualDolbyEvidenceTest {
    @Test
    fun `both outputs must be reproven after a disruptive event`() {
        val proven =
            YDualDolbyEvidenceState()
                .observeVideo(outputVerified = true, dolbyVisionVerified = true)
                .observeAudio(
                    outputVerified = true,
                    atmosSourceDetected = true,
                    outputMode = YDolbyAtmosOutputMode.Eac3JocPassthrough,
                )
        assertTrue(proven.nativeDualDolbyOutput)

        val invalidated = proven.invalidate(YOutputEvidenceResetReason.SurfaceChanged)
        assertEquals(1L, invalidated.generation)
        assertFalse(invalidated.nativeDualDolbyOutput)

        val videoOnly = invalidated.observeVideo(outputVerified = true, dolbyVisionVerified = true)
        assertFalse(videoOnly.nativeDualDolbyOutput)
        assertTrue(
            videoOnly
                .observeAudio(
                    outputVerified = true,
                    atmosSourceDetected = true,
                    outputMode = YDolbyAtmosOutputMode.Eac3JocPassthrough,
                ).nativeDualDolbyOutput,
        )
    }

    @Test
    fun `unverified audio progress cannot retain an Atmos claim`() {
        val state =
            YDualDolbyEvidenceState()
                .observeAudio(
                    outputVerified = false,
                    atmosSourceDetected = true,
                    outputMode = YDolbyAtmosOutputMode.TrueHdAtmosPassthrough,
                )

        assertEquals(YDolbyAtmosOutputMode.None, state.dolbyAtmosOutputMode)
        assertFalse(state.nativeDualDolbyOutput)
    }

    @Test
    fun `diagnostics invalidation clears all physical output proof atomically`() {
        val diagnostics =
            YPlayerDiagnostics(
                videoOutputVerified = true,
                audioOutputVerified = true,
                dolbyVisionOutput = true,
                dolbyVisionRpuApplied = true,
                dolbyVisionEnhancementLayerDelivered = true,
                immersiveAudioCarrierOutput = true,
                dolbyAtmosSourceDetected = true,
                dolbyAtmosOutputMode = YDolbyAtmosOutputMode.Eac3JocPassthrough,
                dolbyAtmosOutput = true,
                audioOutputRouteVerified = true,
            ).invalidateOutputEvidence(YOutputEvidenceResetReason.Seek)

        assertEquals(1L, diagnostics.outputEvidenceGeneration)
        assertEquals(YOutputEvidenceResetReason.Seek, diagnostics.outputEvidenceResetReason)
        assertFalse(diagnostics.nativeDualDolbyOutput)
        assertFalse(diagnostics.videoOutputVerified)
        assertFalse(diagnostics.audioOutputVerified)
        assertFalse(diagnostics.dolbyVisionRpuApplied)
        assertFalse(diagnostics.dolbyVisionEnhancementLayerDelivered)
    }
}
