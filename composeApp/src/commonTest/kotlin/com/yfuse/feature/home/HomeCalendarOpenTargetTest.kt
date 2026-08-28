package com.yfuse.feature.home

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeCalendarOpenTargetTest {
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
}
