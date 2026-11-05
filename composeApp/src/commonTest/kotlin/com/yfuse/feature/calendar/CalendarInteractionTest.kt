package com.yfuse.feature.calendar

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.backend.CalendarBackendApi
import com.yfuse.backend.OfficialScheduleEnvelope
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarIdentityResolver
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.data.OfficialAiringScheduleCatalog
import com.yfuse.core.data.SeriesAiringScheduleFallback
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.security.TestSecureStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarInteractionTest {
    @Test
    fun show_all_clears_combined_filters_and_persists_the_reset() =
        withCalendar {
            val days = listOf(CalendarDay(show.episode.airDate, listOf(show)))
            component.store.accept(CalendarIntent.ApplySeriesRefresh(show.episode.showTmdbId, days))
            component.store.accept(CalendarIntent.SelectFilter(CalendarFilter.Mine))
            component.store.accept(CalendarIntent.SelectPlatform("其他平台"))
            component.store.accept(CalendarIntent.SelectContent(CalendarContentFilter.Movies))
            assertTrue(component.store.state.filteredToNothing)

            component.store.accept(CalendarIntent.ResetFilters)

            assertEquals(days, component.store.state.visibleDays)
            assertEquals(CalendarFilter.All, component.store.state.filter)
            assertNull(component.store.state.platform)
            assertEquals(CalendarContentFilter.All, component.store.state.contentFilter)
            val restored = CalendarFollowStore(settings)
            assertNull(restored.savedPlatformFilter())
            assertEquals(CalendarContentFilter.All.name, restored.savedContentFilter())
        }

    @Test
    fun opening_or_following_a_movie_never_fetches_or_changes_the_same_id_tv_series() =
        withCalendar {
            val original =
                FollowedSeries(show.episode.showTmdbId, "剧集", reminderMode = CalendarReminderMode.AtBroadcast)
            follows.follow(original)
            val movie =
                show.copy(
                    episode = show.episode.copy(kind = AiringKind.Movie, seasonNumber = 0, episodeNumber = 0),
                )
            assertFalse(follows.isFollowing(movie.episode))
            val before = seriesRequests.toList()

            component.toggleFollow(movie)
            val rows = component.seriesCalendar(movie).getOrThrow().flatMap(CalendarDay::entries)

            assertEquals(listOf(original), follows.followed.value)
            assertEquals(before, seriesRequests)
            assertEquals(listOf(movie.episode.mediaKey), rows.map { it.episode.mediaKey })
            assertFalse(rows.single().followed)
        }

    private fun withCalendar(block: suspend Fixture.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val fixture = Fixture()
            try {
                fixture.block()
            } finally {
                fixture.lifecycle.destroy()
                fixture.client.close()
                Dispatchers.resetMain()
            }
        }

    private class Fixture {
        val settings = MapSettings()
        val client = HttpClient(MockEngine { error("No media server configured") })
        val lifecycle = LifecycleRegistry()
        val follows = CalendarFollowStore(settings)
        val seriesRequests = mutableListOf<Int>()
        val registry = ServerRegistry(settings, TestSecureStore())
        val catalog =
            OfficialAiringScheduleCatalog(
                object : CalendarBackendApi {
                    override val enabled = false

                    override suspend fun fetch(revision: String): OfficialScheduleEnvelope? = error("Backend disabled")
                },
                settings,
            )
        val repository =
            AiringCalendarRepository(
                EmbyRepository(client),
                registry,
                catalog,
                CalendarIdentityResolver(catalog, settings),
                follows,
                supplementalSchedules =
                    SeriesAiringScheduleFallback(fetch = { id, _ ->
                        seriesRequests += id
                        Result.success(emptyList())
                    }),
            )
        val component =
            CalendarComponent(
                DefaultComponentContext(
                    lifecycle,
                ),
                DefaultStoreFactory(),
                repository,
                follows,
                registry,
                {
                },
                { _, _ -> },
            )
        val show =
            CalendarEntry(
                AiringEpisode(123, "剧集", null, 1, 1, null, "2026-11-04", ShowOrigin.Foreign),
                LibraryStatus.Unknown,
            )
    }
}
