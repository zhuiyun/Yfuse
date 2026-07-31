package com.yfuse.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DateWindowTest {

    @Test
    fun shifts_within_a_month() {
        assertEquals("2026-07-31", shiftIsoDate("2026-07-24", 7))
        assertEquals("2026-07-17", shiftIsoDate("2026-07-24", -7))
    }

    @Test
    fun crosses_month_and_year_boundaries() {
        assertEquals("2026-08-03", shiftIsoDate("2026-07-31", 3))
        assertEquals("2027-01-01", shiftIsoDate("2026-12-31", 1))
        assertEquals("2025-12-31", shiftIsoDate("2026-01-01", -1))
    }

    @Test
    fun handles_leap_days() {
        // 2028 is a leap year; 2027 is not.
        assertEquals("2028-02-29", shiftIsoDate("2028-02-28", 1))
        assertEquals("2027-03-01", shiftIsoDate("2027-02-28", 1))
        // 2100 is divisible by 100 but not 400, so it is not a leap year.
        assertEquals("2100-03-01", shiftIsoDate("2100-02-28", 1))
    }

    @Test
    fun counts_days_between_dates_in_both_directions() {
        assertEquals(7, daysBetweenIso("2026-07-24", "2026-07-31"))
        assertEquals(-7, daysBetweenIso("2026-07-31", "2026-07-24"))
        assertEquals(0, daysBetweenIso("2026-07-31", "2026-07-31"))
        assertEquals(366, daysBetweenIso("2027-12-31", "2028-12-31"))
    }

    @Test
    fun leaves_unparseable_input_alone_rather_than_inventing_a_date() {
        assertEquals("", shiftIsoDate("", 1))
        assertEquals("tomorrow", shiftIsoDate("tomorrow", 1))
        assertEquals("2026-07", shiftIsoDate("2026-07", 1))
        assertEquals(0, daysBetweenIso("2026-07-31", "not-a-date"))
    }
}
