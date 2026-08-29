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
            videoOnly.copy(
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
}
