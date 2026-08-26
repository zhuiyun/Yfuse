package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfficialAiringScheduleCatalogTest {
    private val catalog =
        OfficialAiringScheduleCatalog(
            client = HttpClient(MockEngine { error("Unexpected remote refresh") }),
            settings = MapSettings(),
        )

    @Test
    fun live_action_schedule_matches_the_revised_official_member_calendar() {
        val episodes = catalog.series(272938, "fallback").orEmpty()

        assertEquals(21, episodes.size)
        assertEquals((1..21).toList(), episodes.map { it.episodeNumber })
        assertEquals(listOf("2026-08-19", "2026-08-19", "2026-08-19"), episodes.take(3).map { it.airDate })
        assertEquals("2026-08-24", episodes.single { it.episodeNumber == 12 }.airDate)
        assertEquals("2026-08-25", episodes.single { it.episodeNumber == 13 }.airDate)
        assertEquals("2026-08-25", episodes.single { it.episodeNumber == 14 }.airDate)
        assertEquals("2026-08-30", episodes.last().airDate)
        assertEquals(setOf(AiringScheduleAuthority.Official), episodes.map { it.scheduleAuthority }.toSet())
        assertEquals(setOf("12:00"), episodes.mapNotNull { it.airTime }.toSet())
        assertEquals(listOf("优酷", "爱奇艺"), episodes.first().platforms)
        assertEquals(setOf(100), episodes.mapNotNull { it.scheduleConfidence }.toSet())
        assertEquals(setOf("师兄太稳健官微"), episodes.flatMap { it.scheduleEvidence }.map { it.publisher }.toSet())
        assertEquals(setOf("/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg"), episodes.mapNotNull { it.posterPath }.toSet())
    }

    @Test
    fun animation_with_a_similar_name_is_not_matched() {
        assertNull(catalog.series(218642, "师兄啊师兄"))
    }

    @Test
    fun official_coordinate_replaces_tmdb_date() {
        val tmdb = episode(number = 13, date = "2026-08-26", poster = "/poster.jpg")
        val official = catalog.series(272938, "师兄太稳健").orEmpty().single { it.episodeNumber == 13 }

        val merged = mergeAiringSchedules(listOf(tmdb), listOf(official)).single()

        assertEquals("2026-08-25", merged.airDate)
        assertEquals("/pV38dHjE2fPWmd0ltJQpBdbpz7g.jpg", merged.posterPath)
        assertEquals(AiringScheduleAuthority.Official, merged.scheduleAuthority)
    }

    @Test
    fun an_undecorated_official_row_reuses_another_row_poster_from_the_same_show() {
        val tmdb = episode(number = 12, date = "2026-08-24", poster = "/poster.jpg")
        val official =
            catalog
                .series(272938, "师兄太稳健")
                .orEmpty()
                .single { it.episodeNumber == 13 }
                .copy(posterPath = null)

        val merged = mergeAiringSchedules(listOf(tmdb), listOf(official))

        assertEquals("/poster.jpg", merged.single { it.episodeNumber == 13 }.posterPath)
    }

    private fun episode(
        number: Int,
        date: String,
        poster: String?,
    ) = AiringEpisode(
        showTmdbId = 272938,
        showTitle = "师兄太稳健",
        posterPath = poster,
        seasonNumber = 1,
        episodeNumber = number,
        episodeTitle = null,
        airDate = date,
        origin = ShowOrigin.Domestic,
    )

    @Test
    fun double_digit_revisions_are_newer_than_single_digit_revisions() {
        assertTrue(calendarRevisionIsAtLeast("2026-08-23-r10", "2026-08-23-r2"))
        assertTrue(calendarRevisionIsAtLeast("2026-08-24-r1", "2026-08-23-r99"))
        assertFalse(calendarRevisionIsAtLeast("2026-08-23-r1", "2026-08-23-r2"))
    }
}
