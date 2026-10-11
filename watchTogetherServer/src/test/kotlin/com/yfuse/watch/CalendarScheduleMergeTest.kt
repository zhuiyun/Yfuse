package com.yfuse.watch

import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarScheduleMergeTest {
    @Test
    fun a_daily_post_keeps_the_future_dates_from_the_complete_calendar() {
        val previous = schedule(listOf(CalendarEpisode(1, "2026-10-11"), CalendarEpisode(2, "2026-10-12")))
        val update = schedule(listOf(CalendarEpisode(1, "2026-10-11"))).copy(revision = "2026-10-11-r2")
        val merged = mergeCollectedCalendarSchedule(previous, update)
        assertEquals(listOf("2026-10-11", "2026-10-12"), merged.episodes.map { it.airDate })
        assertEquals(update.revision, merged.revision)
    }

    @Test
    fun a_published_correction_updates_its_coordinate_and_keeps_other_dates() {
        val previous = schedule(listOf(CalendarEpisode(1, "2026-10-11"), CalendarEpisode(2, "2026-10-12")))
        val update = schedule(listOf(CalendarEpisode(1, "2026-10-10")))
        assertEquals(
            listOf("2026-10-10", "2026-10-12"),
            mergeCollectedCalendarSchedule(previous, update).episodes.map { it.airDate },
        )
    }

    @Test
    fun seasons_and_access_tiers_are_never_spliced_together() {
        val previous = schedule(listOf(CalendarEpisode(1, "2026-10-11"), CalendarEpisode(2, "2026-10-12")))
        val otherSeason = schedule(listOf(CalendarEpisode(1, "2026-10-20"))).copy(seasonNumber = 2)
        val otherTier = otherSeason.copy(seasonNumber = 1, accessTier = "Free")
        assertEquals(otherSeason, mergeCollectedCalendarSchedule(previous, otherSeason))
        assertEquals(otherTier, mergeCollectedCalendarSchedule(previous, otherTier))
    }

    private fun schedule(episodes: List<CalendarEpisode>) =
        CalendarSeries(
            tmdbId = 42,
            title = "剧集",
            seasonNumber = 1,
            platforms = listOf("腾讯视频"),
            accessTier = "Member",
            sourceUrl = "https://v.qq.com/example",
            revision = "2026-10-11-r1",
            updatedAt = "2026-10-11T01:00:00Z",
            episodes = episodes,
        )
}
