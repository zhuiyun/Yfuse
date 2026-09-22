package com.yfuse.core2.drm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YDrmRenewalPolicyTest {
    @Test
    fun `media drm event forces key renewal without status fields`() {
        assertTrue(shouldRenewDrmKeys(eventRequested = true, status = emptyMap()))
    }

    @Test
    fun `expiring license or playback window renews before zero`() {
        assertTrue(
            shouldRenewDrmKeys(
                eventRequested = false,
                status = mapOf("LicenseDurationRemaining" to "45"),
            ),
        )
        assertTrue(
            shouldRenewDrmKeys(
                eventRequested = false,
                status =
                    mapOf(
                        "LicenseDurationRemaining" to "3600",
                        "PlaybackDurationRemaining" to "60",
                    ),
            ),
        )
    }

    @Test
    fun `healthy or non-numeric status does not churn the license server`() {
        assertFalse(
            shouldRenewDrmKeys(
                eventRequested = false,
                status = mapOf("LicenseDurationRemaining" to "3600"),
            ),
        )
        assertFalse(
            shouldRenewDrmKeys(
                eventRequested = false,
                status = mapOf("LicenseDurationRemaining" to "Unlimited"),
            ),
        )
    }
}
