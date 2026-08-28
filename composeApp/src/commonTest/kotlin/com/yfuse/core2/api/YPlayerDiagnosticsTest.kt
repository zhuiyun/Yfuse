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
}
