package com.yfuse.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun names_the_weekday_a_date_falls_on() {
        // 1970-01-01 is the anchor the epoch-day arithmetic is offset from.
        assertEquals("周四", isoWeekdayLabel("1970-01-01"))
        assertEquals("周三", isoWeekdayLabel("1969-12-31"))
        assertEquals("周六", isoWeekdayLabel("2026-08-01"))
        assertEquals("周日", isoWeekdayLabel("2026-08-02"))
        assertEquals("周一", isoWeekdayLabel("2026-08-03"))
        assertEquals("", isoWeekdayLabel("not-a-date"))
    }

    @Test
    fun shortens_a_date_for_a_chip() {
        assertEquals("8-1", isoShortDate("2026-08-01"))
        assertEquals("12-25", isoShortDate("2026-12-25"))
        assertEquals("not-a-date", isoShortDate("not-a-date"))
    }

    @Test
    fun the_daily_pick_is_stable_within_a_day_and_moves_on_overnight() {
        val pool = listOf("a", "b", "c", "d", "e")

        // Same date, same answer — reopening the app must not reshuffle it.
        assertEquals(pool.pickForDay("2026-08-01"), pool.pickForDay("2026-08-01"))
        assertTrue(pool.pickForDay("2026-08-01") != pool.pickForDay("2026-08-02"))
    }

    @Test
    fun the_daily_pick_walks_the_whole_pool_before_repeating() {
        val pool = listOf("a", "b", "c", "d", "e")
        val week = (1..5).map { day -> pool.pickForDay("2026-08-0$day") }

        assertEquals(pool.size, week.distinct().size)
        // And wraps rather than running off the end.
        assertEquals(pool.pickForDay("2026-08-01"), pool.pickForDay("2026-08-06"))
    }

    @Test
    fun the_daily_pick_copes_with_an_empty_pool_a_bad_date_and_dates_before_1970() {
        assertEquals(null, emptyList<String>().pickForDay("2026-08-01"))
        // An unparseable date still has to yield something rather than nothing.
        assertEquals("a", listOf("a", "b").pickForDay("not-a-date"))
        // A negative epoch day must not index backwards out of the list.
        assertTrue(listOf("a", "b", "c").pickForDay("1969-01-01") in listOf("a", "b", "c"))
    }

    @Test
    fun the_epoch_day_is_anchored_where_the_weekday_maths_expects() {
        assertEquals(0L, isoEpochDay("1970-01-01"))
        assertEquals(1L, isoEpochDay("1970-01-02"))
        assertEquals(-1L, isoEpochDay("1969-12-31"))
        assertEquals(null, isoEpochDay("not-a-date"))
    }
}
