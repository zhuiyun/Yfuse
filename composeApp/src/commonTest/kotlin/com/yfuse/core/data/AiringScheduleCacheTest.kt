package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiringScheduleCacheTest {
    private val window = "2026-07-24..2026-08-14"

    private fun episode(id: Int) =
        AiringEpisode(
            showTmdbId = id,
            showTitle = "剧 $id",
            posterPath = "/p$id.jpg",
            seasonNumber = 1,
            episodeNumber = 3,
            episodeTitle = null,
            airDate = "2026-07-31",
            origin = ShowOrigin.Domestic,
        )

    @Test
    fun round_trips_within_the_same_day_and_window() {
        val cache = AiringScheduleCache(MapSettings())
        val episodes = listOf(episode(1), episode(2))

        cache.write("2026-07-31", window, episodes)

        assertEquals(episodes, cache.read("2026-07-31", window))
    }

    @Test
    fun a_new_day_invalidates_it_because_there_are_new_episodes_to_place() {
        val cache = AiringScheduleCache(MapSettings())
        cache.write("2026-07-31", window, listOf(episode(1)))

        assertNull(cache.read("2026-08-01", window))
    }

    @Test
    fun a_different_window_is_not_the_same_schedule() {
        val cache = AiringScheduleCache(MapSettings())
        cache.write("2026-07-31", window, listOf(episode(1)))

        assertNull(cache.read("2026-07-31", "2026-07-01..2026-07-31"))
    }

    @Test
    fun writing_nothing_clears_rather_than_storing_an_empty_schedule() {
        val cache = AiringScheduleCache(MapSettings())
        cache.write("2026-07-31", window, listOf(episode(1)))

        cache.write("2026-07-31", window, emptyList())

        assertNull(cache.read("2026-07-31", window))
    }

    @Test
    fun unreadable_content_is_discarded_instead_of_failing_every_open() {
        val settings = MapSettings()
        val cache = AiringScheduleCache(settings)
        settings.putString("calendar.schedule.episodes", "{ not json")
        settings.putString("calendar.schedule.fetchedOn", "2026-07-31")
        settings.putString("calendar.schedule.window", window)

        assertNull(cache.read("2026-07-31", window))
        assertNull(settings.getStringOrNull("calendar.schedule.episodes"))
    }
}
