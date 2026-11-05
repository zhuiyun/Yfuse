package com.yfuse.feature.home

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeCalendarOpenTargetTest {
    @Test
    fun homepage_keeps_movie_and_tv_cards_with_the_same_tmdb_id_separate() {
        val show = calendarEntry(tmdbId = 1234)
        val movie =
            show.copy(
                episode = show.episode.copy(kind = AiringKind.Movie, seasonNumber = 0, episodeNumber = 0),
            )
        val cards = homeCalendarPreviews(listOf(CalendarDay(show.episode.airDate, listOf(show, movie))), HomeState())
        assertEquals(setOf(show, movie), cards.map { it.entry }.toSet())
        assertEquals(2, cards.size)
    }

    @Test
    fun official_row_without_emby_identity_opens_matching_resume_series() {
        val server =
            SavedServer(
                id = "server-a",
                baseUrl = "https://example.invalid",
                serverName = "家庭影院",
                userId = "user",
                userName = "用户",
                accessToken = "token",
            )
        val resumeEpisode =
            MediaItem(
                id = "episode-3",
                title = "师兄太稳健",
                subtitle = "S1E3",
                type = "Episode",
                posterItemId = "series-42",
                posterTag = null,
                backdropItemId = "series-42",
                backdropTag = null,
                playedPercentage = 25.0,
                providerIds = emptyMap(),
            )
        val entry =
            CalendarEntry(
                episode =
                    AiringEpisode(
                        showTmdbId = 0,
                        showTitle = "师兄太稳健",
                        posterPath = null,
                        seasonNumber = 1,
                        episodeNumber = 4,
                        episodeTitle = null,
                        airDate = "2026-08-27",
                        origin = ShowOrigin.Domestic,
                    ),
                status = LibraryStatus.Unknown,
            )

        val target = HomeState(resume = listOf(HomeResumeEntry(resumeEpisode, server))).calendarOpenTarget(entry)

        assertEquals(HomeCalendarOpenTarget("server-a", "series-42"), target)
    }

    @Test
    fun calendar_identity_uses_only_an_active_owning_server() {
        val entry =
            calendarEntry().copy(
                seriesItemId = "series-42",
                serverId = "removed-server",
                sources =
                    listOf(
                        CalendarSource(
                            serverId = "server-a",
                            serverName = "家庭影院",
                            seriesItemId = "series-42",
                            status = LibraryStatus.Available,
                        ),
                    ),
            )

        assertEquals(
            HomeCalendarOpenTarget("server-a", "series-42"),
            entry.directCalendarOpenTarget(setOf("server-a")),
        )
        assertNull(entry.directCalendarOpenTarget(setOf("another-server")))
    }

    @Test
    fun tmdb_calendar_card_has_an_immediate_detail_target() {
        val detail = calendarEntry(tmdbId = 1234).tmdbCalendarDetailItem()

        assertEquals(1234, detail?.id)
        assertEquals("师兄太稳健", detail?.title)
        assertEquals("tv", detail?.mediaType)
        assertEquals("2026", detail?.year)
    }

    private fun calendarEntry(tmdbId: Int = 0): CalendarEntry =
        CalendarEntry(
            episode =
                AiringEpisode(
                    showTmdbId = tmdbId,
                    showTitle = "师兄太稳健",
                    posterPath = null,
                    seasonNumber = 1,
                    episodeNumber = 4,
                    episodeTitle = null,
                    airDate = "2026-08-27",
                    origin = ShowOrigin.Domestic,
                ),
            status = LibraryStatus.Unknown,
        )
}
