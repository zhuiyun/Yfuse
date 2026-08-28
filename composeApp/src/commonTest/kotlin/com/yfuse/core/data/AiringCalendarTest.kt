package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDataIssue
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.util.scheduledEpochMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiringCalendarTest {
    private val today = "2026-07-31"

    @Test
    fun active_library_selection_includes_next_up_favourites_and_recent_series() {
        fun identity(
            id: String,
            created: String,
            favorite: Boolean = false,
        ) = LibrarySeriesIdentity(
            itemId = id,
            title = "剧集 $id",
            year = 2026,
            providerIds = mapOf("Tmdb" to id.filter(Char::isDigit).ifBlank { "1" }),
            dateCreated = created,
            isFavorite = favorite,
        )

        val selected =
            selectActiveLibrarySeries(
                catalog =
                    listOf(
                        identity("next-1", "2026-01-01"),
                        identity("favorite-2", "2026-01-02", favorite = true),
                        identity("recent-3", "2026-08-03"),
                        identity("recent-4", "2026-08-04"),
                        identity("old-5", "2025-01-01"),
                    ),
                nextUpSeriesIds = setOf("next-1"),
                recentLimit = 2,
                recentCutoffDate = "2026-05-01",
            )

        assertEquals(
            listOf("next-1", "favorite-2", "recent-4", "recent-3"),
            selected.map(LibrarySeriesIdentity::itemId),
        )
    }

    @Test
    fun automatic_recent_selection_excludes_old_rows_but_keeps_next_up_and_favourites() {
        fun identity(
            id: String,
            created: String,
            favorite: Boolean = false,
        ) = LibrarySeriesIdentity(
            itemId = id,
            title = id,
            year = 2026,
            providerIds = mapOf("Tmdb" to id.filter(Char::isDigit).ifBlank { "1" }),
            dateCreated = created,
            isFavorite = favorite,
        )

        val selected =
            selectActiveLibrarySeries(
                catalog =
                    listOf(
                        identity("next-1", "2024-01-01T00:00:00Z"),
                        identity("favorite-2", "2024-01-01T00:00:00Z", favorite = true),
                        identity("recent-3", "2026-08-20T00:00:00Z"),
                        identity("old-4", "2026-01-01T00:00:00Z"),
                    ),
                nextUpSeriesIds = setOf("next-1"),
                recentLimit = 30,
                recentCutoffDate = "2026-05-29",
            )

        assertEquals(listOf("next-1", "favorite-2", "recent-3"), selected.map(LibrarySeriesIdentity::itemId))
    }

    private fun episode(
        played: Boolean,
        resumePositionTicks: Long? = null,
        indexNumber: Int = 5,
        seasonNumber: Int = 2,
    ) = Episode(
        id = "ep$indexNumber",
        name = "第 $indexNumber 集",
        indexNumber = indexNumber,
        seasonNumber = seasonNumber,
        seasonId = "s$seasonNumber",
        overview = null,
        runtimeMinutes = 45,
        primaryTag = null,
        playedPercentage = null,
        played = played,
        resumePositionTicks = resumePositionTicks,
    )

    @Test
    fun an_episode_on_the_server_is_available_or_watched() {
        assertEquals(
            LibraryStatus.Available,
            classifyAiring(episode(played = false), airDate = "2026-07-30", today = today),
        )
        assertEquals(
            LibraryStatus.Watched,
            classifyAiring(episode(played = true), airDate = "2026-07-30", today = today),
        )
    }

    @Test
    fun a_partially_watched_episode_is_in_progress() {
        assertEquals(
            LibraryStatus.InProgress,
            classifyAiring(
                episode(played = false, resumePositionTicks = 60_000_000L),
                airDate = "2026-07-30",
                today = today,
            ),
        )
    }

    @Test
    fun an_aired_episode_the_server_lacks_is_the_gap_worth_showing() {
        assertEquals(
            LibraryStatus.Missing,
            classifyAiring(match = null, airDate = "2026-07-30", today = today),
        )
        // Airing today counts as aired: the calendar should not call today's episode
        // "upcoming" for the rest of the day.
        assertEquals(
            LibraryStatus.Missing,
            classifyAiring(match = null, airDate = today, today = today),
        )
    }

    @Test
    fun a_future_episode_is_not_missing_merely_because_nobody_has_it() {
        assertEquals(
            LibraryStatus.Unaired,
            classifyAiring(match = null, airDate = "2026-08-07", today = today),
        )
    }

    @Test
    fun a_future_episode_already_on_the_server_still_reads_as_available() {
        // Servers do get episodes early — a pre-air leak, or a show whose TMDB date is
        // simply wrong. What is on disk outranks what the schedule predicted.
        assertEquals(
            LibraryStatus.Available,
            classifyAiring(episode(played = false), airDate = "2026-08-07", today = today),
        )
    }

    @Test
    fun media_key_matches_the_coordinate_emby_lookup_resolves() {
        val airing =
            AiringEpisode(
                showTmdbId = 1399,
                showTitle = "权力的游戏",
                posterPath = null,
                seasonNumber = 2,
                episodeNumber = 5,
                episodeTitle = null,
                airDate = today,
                origin = ShowOrigin.Foreign,
            )
        assertEquals("tmdb:1399/s2e5", airing.mediaKey)
    }

    @Test
    fun detail_preview_uses_the_known_series_and_episode_without_provider_lookup() {
        val scheduled =
            AiringEpisode(
                showTmdbId = 272938,
                showTitle = "师兄太稳健",
                posterPath = "/poster.jpg",
                seasonNumber = 1,
                episodeNumber = 13,
                episodeTitle = null,
                airDate = "2026-08-25",
                origin = ShowOrigin.Domestic,
            )
        val hint =
            SeriesCalendarLibraryHint(
                showTmdbId = 272938,
                server =
                    SavedServer(
                        id = "server",
                        baseUrl = "https://example.invalid",
                        serverName = "家庭影院",
                        userId = "user",
                        userName = "用户",
                        accessToken = "token",
                    ),
                seriesItemId = "series-item",
                episodes = listOf(episode(played = false, indexNumber = 13, seasonNumber = 1)),
            )

        val entry =
            calendarPreviewDays(
                listOf(scheduled),
                today = "2026-08-25",
                libraryHint = hint,
            ).single().entries.single()

        assertEquals(LibraryStatus.Available, entry.status)
        assertEquals("ep13", entry.itemId)
        assertEquals("series-item", entry.seriesItemId)
        assertEquals(listOf("家庭影院"), entry.serverNames)
        assertEquals(1, entry.libraryEpisodeCount)
        assertEquals(13, entry.highestLibraryEpisodeNumber)
        assertTrue(entry.posterUrls.single().contains("/Items/series-item/Images/Primary"))
    }

    @Test
    fun official_noon_release_does_not_turn_missing_at_midnight() {
        val scheduled =
            AiringEpisode(
                showTmdbId = 272938,
                showTitle = "师兄太稳健",
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = 13,
                episodeTitle = null,
                airDate = "2026-08-25",
                origin = ShowOrigin.Domestic,
                scheduleAuthority = AiringScheduleAuthority.Official,
                airTime = "12:00",
                timeZoneId = "Asia/Shanghai",
            )
        val noon = checkNotNull(scheduledEpochMillis("2026-08-25", "12:00", "Asia/Shanghai"))

        assertEquals(
            LibraryStatus.Unaired,
            classifyAiring(null, scheduled, today = "2026-08-25", nowEpochMs = noon - 1),
        )
        assertEquals(
            LibraryStatus.Missing,
            classifyAiring(null, scheduled, today = "2026-08-25", nowEpochMs = noon),
        )
    }

    @Test
    fun unrelated_discovery_rows_do_not_inflate_the_missing_count() {
        val scheduled =
            AiringEpisode(
                showTmdbId = 42,
                showTitle = "发现内容",
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = 1,
                episodeTitle = null,
                airDate = today,
                origin = ShowOrigin.Foreign,
            )

        val discovery =
            CalendarEntry(
                episode = scheduled,
                status = LibraryStatus.Missing,
                discoveryOnly = true,
            )
        val followed =
            discovery.copy(
                followed = true,
                discoveryOnly = false,
            )

        assertEquals(0, CalendarDay(today, listOf(discovery)).missingCount)
        assertEquals(1, CalendarDay(today, listOf(followed)).missingCount)
    }

    @Test
    fun in_progress_source_wins_over_an_already_watched_copy() {
        val scheduled =
            AiringEpisode(
                showTmdbId = 7,
                showTitle = "多服务器剧集",
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = 2,
                episodeTitle = null,
                airDate = today,
                origin = ShowOrigin.Foreign,
            )
        val merged =
            mergeCalendarEntries(
                episode = scheduled,
                candidates =
                    listOf(
                        CalendarEntry(
                            scheduled,
                            LibraryStatus.Watched,
                            itemId = "watched",
                            serverId = "a",
                            seriesItemId = "sa",
                        ),
                        CalendarEntry(
                            scheduled,
                            LibraryStatus.InProgress,
                            itemId = "resume",
                            serverId = "b",
                            seriesItemId = "sb",
                        ),
                    ),
                today = today,
            )

        assertEquals(LibraryStatus.InProgress, merged.status)
        assertEquals("resume", merged.itemId)
        assertEquals("b", merged.serverId)
    }

    @Test
    fun a_known_series_lookup_failure_beats_an_unrelated_server_miss() {
        val scheduled =
            AiringEpisode(
                showTmdbId = 8,
                showTitle = "已识别剧集",
                posterPath = null,
                seasonNumber = 1,
                episodeNumber = 3,
                episodeTitle = null,
                airDate = today,
                origin = ShowOrigin.Foreign,
            )
        val merged =
            mergeCalendarEntries(
                episode = scheduled,
                candidates =
                    listOf(
                        CalendarEntry(
                            scheduled,
                            LibraryStatus.Unknown,
                            serverId = "a",
                            seriesItemId = "series",
                            dataIssue = CalendarDataIssue.LibraryLookupFailed,
                        ),
                        CalendarEntry(
                            scheduled,
                            LibraryStatus.Missing,
                            discoveryOnly = true,
                        ),
                    ),
                today = today,
            )

        assertEquals(LibraryStatus.Unknown, merged.status)
        assertEquals("series", merged.seriesItemId)
        assertEquals(CalendarDataIssue.LibraryLookupFailed, merged.dataIssue)
    }
}
