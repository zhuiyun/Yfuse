package com.yfuse.core.offline

import android.app.ForegroundServiceStartNotAllowedException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineForegroundRefusalTest {
    @Test
    fun a_refused_foreground_service_lets_a_background_run_carry_on() {
        val refused = ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")

        assertTrue(isForegroundServiceStartRefusal(refused, sdkInt = 31))
        assertTrue(isForegroundServiceStartRefusal(refused, sdkInt = 36))
    }

    @Test
    fun any_other_state_error_is_not_taken_for_the_refusal() {
        assertFalse(isForegroundServiceStartRefusal(IllegalStateException("Not implemented"), sdkInt = 36))
    }

    @Test
    fun nothing_counts_as_the_refusal_before_android_12() {
        val refused = ForegroundServiceStartNotAllowedException("startForegroundService() not allowed")

        assertFalse(isForegroundServiceStartRefusal(refused, sdkInt = 30))
    }
}
