package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.OfficialScheduleEnvelope
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.security.TestSecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeriesAiringScheduleFallbackTest {
    @Test
    fun actual_series_calendar_keeps_published_future_dates_when_metadata_has_no_future_schedule() =
        runTest {
            val settings = MapSettings()
            val publication =
                object : CalendarBackendApi {
                    override val enabled = false

                    override suspend fun fetch(revision: String): OfficialScheduleEnvelope? =
                        error("Disabled backend must not be requested")
                }
            val catalog = OfficialAiringScheduleCatalog(publication, settings)
            val library = HttpClient(MockEngine { error("No server is configured") })
            try {
                val repository =
                    AiringCalendarRepository(
                        EmbyRepository(library),
                        ServerRegistry(settings, TestSecureStore()),
                        catalog,
                        CalendarIdentityResolver(catalog, settings),
                        CalendarFollowStore(settings),
                        supplementalSchedules =
                            SeriesAiringScheduleFallback(fetch = {
                                    _,
                                    _,
                                ->
                                Result.success(emptyList())
                            }),
                    )
                val days = repository.seriesCalendar(294446, "美人余", today = "2026-10-11").getOrThrow()
                val future = days.filter { it.date > "2026-10-11" }

                assertEquals((12..16).map { "2026-10-$it" }, future.map { it.date })
                assertEquals((8..17).toList(), future.flatMap(CalendarDay::entries).map { it.episode.episodeNumber })
                assertTrue(future.flatMap(CalendarDay::entries).all { it.status == LibraryStatus.Unaired })
                assertTrue(days.none { it.date >= "2026-10-17" })
            } finally {
                library.close()
            }
        }

    @Test
    fun exact_lookup_is_shared_and_cached_across_calendar_and_detail() =
        runTest {
            var calls = 0
            val lookup =
                SeriesAiringScheduleFallback(fetch = { _, _ ->
                    calls++
                    delay(10)
                    Result.success(listOf(episode(42, 2, "2026-10-12")))
                })
            val detail = async { lookup.series(42, "剧集") }
            val calendar = async { lookup.series(42, "剧集") }
            assertEquals(detail.await(), calendar.await())
            assertEquals(
                "2026-10-12",
                lookup
                    .series(42, "剧集")
                    .getOrThrow()
                    .single()
                    .airDate,
            )
            assertEquals(1, calls)
        }

    @Test
    fun failed_refresh_keeps_known_dates_without_inventing_future_rows() =
        runTest {
            var fail = false
            val lookup =
                SeriesAiringScheduleFallback(fetch = { _, _ ->
                    if (fail) {
                        Result.failure(IllegalStateException("offline"))
                    } else {
                        Result.success(listOf(episode(42, 1, "2026-10-09")))
                    }
                })
            val before = lookup.series(42, "剧集").getOrThrow()
            fail = true
            assertEquals(before, lookup.series(42, "剧集", forceRefresh = true).getOrThrow())
            assertTrue(before.none { it.airDate > "2026-10-11" })
        }

    @Test
    fun another_identity_or_undated_episode_cannot_enter_a_series_calendar() =
        runTest {
            val lookup =
                SeriesAiringScheduleFallback(fetch = { _, _ ->
                    Result.success(
                        listOf(episode(43, 1, "2026-10-12"), episode(42, 2, ""), episode(42, 3, "2026-10-13")),
                    )
                })
            assertEquals(listOf(3), lookup.series(42, "剧集").getOrThrow().map { it.episodeNumber })
        }

    @Test
    fun publication_corrects_a_fallback_coordinate_without_erasing_other_future_dates() {
        val fallback = listOf(episode(42, 1, "2026-10-12"), episode(42, 2, "2026-10-13"))
        val official = episode(42, 1, "2026-10-11").copy(scheduleAuthority = AiringScheduleAuthority.Official)
        val merged = mergeAiringSchedules(fallback, listOf(official))
        assertEquals(listOf("2026-10-11", "2026-10-13"), merged.map { it.airDate })
        assertEquals(AiringScheduleAuthority.Official, merged.first().scheduleAuthority)
        assertEquals(AiringScheduleAuthority.Tmdb, merged.last().scheduleAuthority)
    }

    private fun episode(
        id: Int,
        number: Int,
        date: String,
    ) = AiringEpisode(
        showTmdbId = id,
        showTitle = "剧集",
        posterPath = null,
        seasonNumber = 1,
        episodeNumber = number,
        episodeTitle = null,
        airDate = date,
        origin = ShowOrigin.Domestic,
    )
}
