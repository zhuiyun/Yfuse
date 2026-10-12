package com.yfuse.feature.calendar

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue("UID:tmdb:1399/s2e3@yfuse" in ics)
        assertTrue("DTSTART:20260825T123000Z" in ics)
        assertTrue("SUMMARY:测试\\,剧 S2 E3" in ics)
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
    }

    @Test
    fun rescheduling_an_episode_keeps_its_calendar_identity() {
        val original = episode()
        val updated = original.copy(airDate = "2026-09-01")
        assertEquals(uid(export(original)), uid(export(updated)))
        assertFalse("DTSTAMP:19700101T000000Z" in export(updated))
    }

    @Test
    fun titles_without_tmdb_ids_and_movies_have_distinct_identities() {
        val episodes =
            listOf(
                episode().copy(showTmdbId = 0, showTitle = "第一部剧"),
                episode().copy(showTmdbId = 0, showTitle = "第二部剧"),
                episode(),
                episode().copy(kind = AiringKind.Movie),
            )
        val entries = episodes.map { CalendarEntry(it, LibraryStatus.Unaired) }
        val ics = buildCalendarIcs(listOf(CalendarDay("2026-08-25", entries)))
        val identities =
            ics
                .lineSequence()
                .filter { it.startsWith("UID:") }
                .distinct()
                .toList()
        assertEquals(4, identities.size)
    }

    @Test
    fun lines_use_crlf_and_fold_utf8_without_corrupting_titles() {
        val title = "长标题😀".repeat(30) + "\r\n第二行\r第三行"
        val ics = export(episode().copy(showTitle = title))
        assertFalse('\n' in ics.replace("\r\n", ""))
        assertFalse('\r' in ics.replace("\r\n", ""))
        assertTrue(ics.split("\r\n").all { it.encodeToByteArray().size <= 75 })
        assertEquals(ics, ics.encodeToByteArray().decodeToString(throwOnInvalidSequence = true))
        val unfolded = ics.replace("\r\n ", "")
        val escaped = "长标题😀".repeat(30) + "\\n第二行\\n第三行"
        assertTrue("SUMMARY:$escaped S2 E3\r\n" in unfolded)
    }

    @Test
    fun source_urls_preserve_uri_punctuation() {
        val url = "https://example.com/schedule;season=2?episodes=3,4"
        assertTrue("URL:$url\r\n" in export(episode().copy(sourceUrl = url)))
        assertFalse("URL:" in export(episode().copy(sourceUrl = "$url\r\nSUMMARY:untrusted")))
    }

    @Test
    fun timezone_conversion_keeps_the_published_instant_across_dst() {
        val summer = episode().copy(airTime = "21:00", timeZoneId = "America/New_York")
        val winter = summer.copy(airDate = "2026-12-25")
        assertTrue("DTSTART:20260826T010000Z" in export(summer))
        assertTrue("DTSTART:20261226T020000Z" in export(winter))
    }

    @Test
    fun an_unknown_timezone_falls_back_to_the_published_date() {
        val ics = export(episode().copy(airTime = "21:00", timeZoneId = "Mars/Olympus"))
        assertTrue("DTSTART;VALUE=DATE:20260825\r\n" in ics)
        assertFalse("TZID=" in ics)
    }

    @Test
    fun an_exact_foreign_release_time_is_exported_without_guessing_a_clock() {
        val ics = export(episode().copy(releaseAtUtc = "2026-08-28T01:00:00Z"))
        assertTrue("DTSTART:20260828T010000Z\r\n" in ics)
    }

    private fun uid(ics: String): String = ics.lineSequence().first { it.startsWith("UID:") }

    private fun export(episode: AiringEpisode): String =
        buildCalendarIcs(listOf(CalendarDay(episode.airDate, listOf(CalendarEntry(episode, LibraryStatus.Unaired)))))

    private fun episode(): AiringEpisode =
        AiringEpisode(
            showTmdbId = 1399,
            showTitle = "测试剧",
            posterPath = null,
            seasonNumber = 2,
            episodeNumber = 3,
            episodeTitle = null,
            airDate = "2026-08-25",
            origin = ShowOrigin.Foreign,
        )
}
