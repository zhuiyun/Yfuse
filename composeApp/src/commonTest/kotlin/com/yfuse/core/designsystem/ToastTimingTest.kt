package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class ToastTimingTest {
    @Test
    fun a_short_notice_keeps_the_base_time() {
        assertEquals(2_600L, toastDurationMillis("已加入收藏"))
        assertEquals(2_600L, toastDurationMillis("x".repeat(12)))
    }

    @Test
    fun longer_copy_earns_reading_time_up_to_a_ceiling() {
        assertEquals(2_680L, toastDurationMillis("x".repeat(13)))
        assertEquals(4_200L, toastDurationMillis("x".repeat(32)))
        assertEquals(7_000L, toastDurationMillis("x".repeat(200)))
    }
}
