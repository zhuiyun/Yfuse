package com.yfuse.core2.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlayerDiagnosticsTest {
    @Test
    fun `native dual Dolby requires both outputs to be verified`() {
        val declaredOnly =
            YPlayerDiagnostics(
                dolbyVisionOutput = true,
                dolbyAtmosOutput = true,
            )
        assertFalse(declaredOnly.nativeDualDolbyOutput)

        val videoOnly =
            declaredOnly.copy(
                videoOutputVerified = true,
            )
        assertFalse(videoOnly.nativeDualDolbyOutput)

        assertTrue(
            videoOnly
                .copy(
                    audioOutputVerified = true,
                ).nativeDualDolbyOutput,
        )
    }

    @Test
    fun `immersive carrier and spatialized pcm are not promoted to native dual Dolby`() {
        val videoVerified =
            YPlayerDiagnostics(
                videoOutputVerified = true,
                audioOutputVerified = true,
                dolbyVisionOutput = true,
                immersiveAudioCarrierOutput = true,
                spatialAudioOutput = true,
                headTrackingAvailable = true,
            )

        assertFalse(videoVerified.nativeDualDolbyOutput)
        assertTrue(videoVerified.copy(dolbyAtmosOutput = true).nativeDualDolbyOutput)
    }

    @Test
    fun `spatialized Atmos source qualifies only for presentation parity`() {
        val diagnostics =
            YPlayerDiagnostics(
                videoOutputVerified = true,
                audioOutputVerified = true,
                dolbyVisionOutput = true,
                dolbyAtmosSourceDetected = true,
                dolbyAtmosOutputMode = YDolbyAtmosOutputMode.AtmosSourceSpatializedPcm,
                spatialAudioOutput = true,
            )

        assertFalse(diagnostics.nativeDualDolbyOutput)
        assertTrue(diagnostics.nativeDualDolbyPresentationOutput)
    }

    @Test
    fun `carrier without exact object evidence is never dual Dolby`() {
        val diagnostics =
            YPlayerDiagnostics(
                videoOutputVerified = true,
                audioOutputVerified = true,
                dolbyVisionOutput = true,
                dolbyAtmosSourceDetected = true,
                immersiveAudioCarrierOutput = true,
                dolbyAtmosOutputMode = YDolbyAtmosOutputMode.TrueHdCarrierPassthrough,
            )

        assertFalse(diagnostics.nativeDualDolbyOutput)
        assertFalse(diagnostics.nativeDualDolbyPresentationOutput)
    }
}
