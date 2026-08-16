package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MpvFileLoadWatchdogTest {
    @Test
    fun only_the_current_buffering_attempt_can_time_out() {
        assertTrue(
            shouldFailMpvFileLoad(
                attempt = 3L,
                activeAttempt = 3L,
                released = false,
                buffering = true,
            ),
        )
        assertFalse(
            shouldFailMpvFileLoad(
                attempt = 2L,
                activeAttempt = 3L,
                released = false,
                buffering = true,
            ),
        )
    }

    @Test
    fun loaded_or_released_attempts_never_trigger_fallback() {
        assertFalse(
            shouldFailMpvFileLoad(
                attempt = 4L,
                activeAttempt = 4L,
                released = false,
                buffering = false,
            ),
        )
        assertFalse(
            shouldFailMpvFileLoad(
                attempt = 4L,
                activeAttempt = 4L,
                released = true,
                buffering = true,
            ),
        )
    }
}
