package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class AiringCalendarMergeTest {
    private val episode =
        AiringEpisode(
            showTmdbId = 1399,
            showTitle = "测试剧",
            posterPath = null,
            seasonNumber = 1,
            episodeNumber = 2,
            episodeTitle = null,
            airDate = "2026-08-24",
            origin = ShowOrigin.Foreign,
        )

    @Test
    fun failed_identified_server_prevents_false_missing_across_servers() {
        val missing =
            CalendarEntry(
                episode = episode,
                status = LibraryStatus.Missing,
                serverId = "a",
                seriesItemId = "series-a",
            )
        val failed =
            CalendarEntry(
                episode = episode,
                status = LibraryStatus.Unknown,
                serverId = "b",
                seriesItemId = "series-b",
                dataIssue = CalendarDataIssue.LibraryLookupFailed,
            )

        val merged = mergeCalendarEntries(episode, listOf(missing, failed), "2026-08-25")

        assertEquals(LibraryStatus.Unknown, merged.status)
        assertEquals(CalendarDataIssue.LibraryLookupFailed, merged.dataIssue)
    }

    @Test
    fun progress_and_quality_follow_the_source_selected_for_opening() {
        val selected =
            CalendarSource(
                serverId = "a",
                serverName = "A",
                itemId = "episode-a",
                status = LibraryStatus.InProgress,
                playedPercentage = 35.0,
                qualityTags = listOf("1080p"),
            )
        val other =
            CalendarSource(
                serverId = "b",
                serverName = "B",
                itemId = "episode-b",
                status = LibraryStatus.Watched,
                playedPercentage = 100.0,
                qualityTags = listOf("4K"),
            )
        val entry =
            CalendarEntry(
                episode = episode,
                status = LibraryStatus.InProgress,
                itemId = "episode-a",
                serverId = "a",
                sources = listOf(selected, other),
            )

        assertEquals(35.0, entry.playedPercentage)
        assertEquals(listOf("1080p"), entry.qualityTags)
    }
}
