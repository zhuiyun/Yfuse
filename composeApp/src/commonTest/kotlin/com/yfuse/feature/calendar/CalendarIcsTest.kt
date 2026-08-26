package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertTrue

class CalendarIcsTest {
    @Test
    fun export_contains_stable_uid_timezone_and_escaped_text() {
        val episode =
            AiringEpisode(
                showTmdbId = 1399,
                showTitle = "测试,剧",
                posterPath = null,
                seasonNumber = 2,
                episodeNumber = 3,
                episodeTitle = null,
                airDate = "2026-08-25",
                origin = ShowOrigin.Foreign,
                airTime = "20:30",
                timeZoneId = "Asia/Shanghai",
                platforms = listOf("平台A"),
            )

        val ics =
            buildCalendarIcs(
                listOf(
                    CalendarDay(
                        date = episode.airDate,
                        entries = listOf(CalendarEntry(episode, LibraryStatus.Unaired)),
                    ),
                ),
            )

        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue("UID:1399-2-3-20260825@yfuse" in ics)
        assertTrue("DTSTART;TZID=Asia/Shanghai:20260825T203000" in ics)
        assertTrue("SUMMARY:测试\\,剧 S2 E3" in ics)
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
    }
}
