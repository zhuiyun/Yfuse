package com.yfuse.feature.detail

import com.yfuse.core.data.CalendarReminderMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesAiringCalendarTest {
    @Test
    fun only_a_series_with_a_numeric_tmdb_provider_id_offers_the_calendar() {
        assertEquals(88, airingCalendarTmdbId("Series", mapOf("Tmdb" to "88")))
        assertEquals(99, airingCalendarTmdbId("series", mapOf("tmdb" to "99")))
        assertNull(airingCalendarTmdbId("Movie", mapOf("Tmdb" to "88")))
        assertNull(airingCalendarTmdbId("Series", mapOf("Imdb" to "tt123")))
        assertNull(airingCalendarTmdbId("Series", mapOf("Tmdb" to "not-a-number")))
    }

    @Test
    fun reminder_mode_cycles_through_every_supported_choice() {
        assertEquals(
            CalendarReminderMode.BeforeAndAtBroadcast,
            nextReminderMode(CalendarReminderMode.Off),
        )
        assertEquals(
            CalendarReminderMode.AtBroadcast,
            nextReminderMode(CalendarReminderMode.BeforeAndAtBroadcast),
        )
        assertEquals(
            CalendarReminderMode.WhenAvailable,
            nextReminderMode(CalendarReminderMode.AtBroadcast),
        )
        assertEquals(
            CalendarReminderMode.Off,
            nextReminderMode(CalendarReminderMode.WhenAvailable),
        )
    }
}
