package com.yfuse.core.data

import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import com.yfuse.watch.protocol.SupplementalCalendarEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Re0CalendarSupplementTest {
    private val row =
        SupplementalCalendarEntry(
            tmdbId = 123,
            title = "Example",
            kind = "tv",
            seasonNumber = 2,
            episodeNumber = 3,
            airDate = "2026-10-30",
            updatedAt = "2026-10-30T03:00:00Z",
        )

    @Test
    fun tmdb_and_library_dates_still_win_over_supplementary_dates() {
        val re0 = re0CalendarEpisodes(listOf(row), "2026-10-30-r1").single()
        val tmdb =
            re0.copy(
                airDate = "2026-11-01",
                scheduleProvider = null,
                scheduleAuthority = AiringScheduleAuthority.Tmdb,
            )
        assertEquals(listOf(tmdb), mergeAiringSchedules(listOf(tmdb), listOf(re0)))
        assertEquals(listOf(re0), mergeAiringSchedules(emptyList(), listOf(re0)))
        val official = tmdb.copy(airDate = "2026-11-02", scheduleAuthority = AiringScheduleAuthority.Official)
        assertEquals(listOf(official), mergeAiringSchedules(listOf(tmdb), listOf(re0, official)))
    }

    @Test
    fun preserves_existing_date_even_when_supplement_disagrees() {
        val supplemental = re0CalendarEpisodes(listOf(row), "2026-10-30-r1")
        val official =
            supplemental.single().copy(
                airDate = "2026-11-01",
                scheduleAuthority = AiringScheduleAuthority.Official,
            )
        val merged = mergeRe0CalendarEpisodes(listOf(official), supplemental)
        assertEquals(listOf(official), merged)
        assertTrue(merged.filter { it.airDate == "2026-10-30" }.isEmpty())
    }

    @Test
    fun movies_and_multiple_seasons_have_separate_coordinates_and_honest_provenance() {
        val episodes =
            re0CalendarEpisodes(
                listOf(row, row.copy(seasonNumber = 3), row.copy(kind = "movie", seasonNumber = 0, episodeNumber = 0)),
                "2026-10-30-r1",
            )
        assertEquals(3, mergeRe0CalendarEpisodes(emptyList(), episodes).size)
        assertEquals(1, episodes.count { it.kind == AiringKind.Movie })
        assertTrue(
            episodes.all {
                it.scheduleProvider == "RE0" &&
                    it.scheduleAuthority == AiringScheduleAuthority.Estimated &&
                    it.origin == ShowOrigin.Unknown
            },
        )
    }

    @Test
    fun invalid_coordinates_do_not_disable_valid_rows() {
        val rows = listOf(row, row.copy(seasonNumber = 0), row.copy(tmdbId = 0), row.copy(airDate = "2026-13-40"))
        assertEquals(1, re0CalendarEpisodes(rows, "2026-10-30-r1").size)
    }

    @Test
    fun exact_time_reaches_existing_reminder_and_display_fields() {
        val episode =
            re0CalendarEpisodes(
                listOf(row.copy(releaseAtUtc = "2026-10-30T03:00:00Z")),
                "2026-10-30-r1",
            ).single()
        assertEquals("11:00", episode.airTime)
        assertEquals("Asia/Shanghai", episode.timeZoneId)
        assertTrue(
            re0CalendarEpisodes(listOf(row.copy(releaseAtUtc = "2026-10-30T23:00:00Z")), "2026-10-30-r1").isEmpty(),
        )
    }
}
