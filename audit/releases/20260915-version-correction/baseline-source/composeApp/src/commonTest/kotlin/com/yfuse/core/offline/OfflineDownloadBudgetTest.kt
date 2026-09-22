package com.yfuse.core.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineDownloadBudgetTest {
    @Test
    fun overnight_window_includes_midnight_but_excludes_end() {
        val policy = OfflineDownloadPolicy(windowStartMinute = 22 * 60, windowEndMinute = 7 * 60)
        assertEquals(0, policy.minutesUntilDownloadWindow(23 * 60))
        assertEquals(0, policy.minutesUntilDownloadWindow(6 * 60))
        assertEquals(15 * 60, policy.minutesUntilDownloadWindow(7 * 60))
        assertEquals(10 * 60, policy.minutesUntilDownloadWindow(12 * 60))
    }

    @Test
    fun daytime_window_and_all_day_have_exact_boundaries() {
        val policy = OfflineDownloadPolicy(windowStartMinute = 9 * 60, windowEndMinute = 18 * 60)
        assertEquals(0, policy.minutesUntilDownloadWindow(9 * 60))
        assertEquals(15 * 60, policy.minutesUntilDownloadWindow(18 * 60))
        assertEquals(0, OfflineDownloadPolicy().minutesUntilDownloadWindow(1439))
    }

    @Test
    fun byte_budget_checks_remaining_capacity_without_overflow() {
        assertTrue(offlineBudgetAllows(100, 80, 20))
        assertFalse(offlineBudgetAllows(100, 80, 21))
        assertFalse(offlineBudgetAllows(Long.MAX_VALUE, Long.MAX_VALUE - 2, 3))
        assertTrue(offlineBudgetAllows(0, 100, 999))
    }
}
