package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals

class AiringCalendarTest {
    private val today = "2026-07-31"

    private fun episode(played: Boolean) =
        Episode(
            id = "ep1",
            name = "第 5 集",
            indexNumber = 5,
            seasonNumber = 2,
            seasonId = "s2",
            overview = null,
            runtimeMinutes = 45,
            primaryTag = null,
            playedPercentage = null,
            played = played,
            resumePositionTicks = null,
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
}
