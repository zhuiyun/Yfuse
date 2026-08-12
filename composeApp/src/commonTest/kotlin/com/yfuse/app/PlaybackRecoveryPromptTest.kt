package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRecoveryPromptTest {
    @Test
    fun recovery_position_has_a_clear_human_readable_time_point() {
        assertEquals("02:03", formatRecoveryPosition(123_999L))
        assertEquals("1:02:03", formatRecoveryPosition(3_723_999L))
        assertEquals("00:00", formatRecoveryPosition(-1L))
    }
}
