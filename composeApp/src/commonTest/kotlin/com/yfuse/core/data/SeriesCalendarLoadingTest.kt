package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.Episode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.TestSecureStore
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SeriesCalendarLoadingTest {
    private val server = SavedServer("server", "https://library.example", "家庭影院", "user", "用户", "token")
    private val episode =
        Episode(
            id = "episode-1",
            name = "第一集",
            indexNumber = 1,
            seasonNumber = 1,
            seasonId = null,
            overview = null,
            runtimeMinutes = null,
            primaryTag = null,
            playedPercentage = null,
            resumePositionTicks = null,
            premiereDate = "2026-09-04",
        )

    @Test
    fun fast_server_reports_playable_while_another_server_and_schedule_refresh_are_still_pending() = runTest {
        val external = client { awaitCancellation() }
        val lookups = mutableListOf<String?>()
        val library = client { request ->
            if (request.url.host == "slow.example") awaitCancellation()
            if (request.url.encodedPath.endsWith("/Episodes")) {
                json("""{"Items":[{"Id":"episode-13","Name":"第13集","Type":"Episode","IndexNumber":13,"ParentIndexNumber":1}]}""")
            } else {
                lookups += request.url.parameters["AnyProviderIdEquals"]
                json("""{"Items":[{"Id":"series","Name":"师兄太稳健","ProviderIds":{"Tmdb":"272938"}}]}""")
            }
        }
        try {
            val settings = MapSettings()
            val registry = ServerRegistry(settings, TestSecureStore()).apply {
                addOrUpdate(server.copy(id = "slow", baseUrl = "https://slow.example"))
                addOrUpdate(server)
            }
            val schedules = OfficialAiringScheduleCatalog(external, settings)
            val repository = AiringCalendarRepository(
                EmbyRepository(library), registry, schedules, CalendarIdentityResolver(schedules, settings),
                CalendarFollowStore(settings),
            )
            val playable = CompletableDeferred<List<CalendarDay>>()
            val load = async {
                repository.seriesCalendar(272938, "师兄太稳健", today = "2026-08-25", onPreview = { days ->
                    if (days.any { day -> day.entries.any { it.itemId == "episode-13" } }) playable.complete(days)
                })
            }
            playable.await()
            assertTrue(currentTime < 5_000, "Availability must not wait for the schedule or slow server")
            assertFalse(load.isCompleted)
            assertEquals(listOf<String?>("tmdb.272938"), lookups)
            val finalRows = load.await().getOrThrow().flatMap(CalendarDay::entries)
            assertTrue(finalRows.any { it.itemId == "episode-13" && it.status == LibraryStatus.Available })
        } finally {
            external.close()
            library.close()
        }
    }

    @Test
    fun known_library_episode_is_visible_before_stalled_external_schedules_finish() =
        runTest {
            val preview = CompletableDeferred<List<CalendarDay>>()
            val external = client { awaitCancellation() }
            val library = client { error("Complete library hint must not issue another lookup") }
            try {
                val repository = repository(external, library)
                val load =
                    async {
                        repository.seriesCalendar(
                            42,
                            "剧集",
                            today = "2026-09-04",
                            libraryHint =
                                SeriesCalendarLibraryHint(
                                    42,
                                    server,
                                    "series",
                                    listOf(episode),
                                    episodesComplete = true,
                                ),
                            onPreview = { preview.complete(it) },
                        )
                    }
                val first =
                    preview
                        .await()
                        .single()
                        .entries
                        .single()
                assertEquals(LibraryStatus.Available, first.status)
                assertEquals("episode-1", first.itemId)
                assertEquals(AiringScheduleAuthority.Library, first.episode.scheduleAuthority)
                assertFalse(load.isCompleted)
                assertEquals(
                    LibraryStatus.Available,
                    load
                        .await()
                        .getOrThrow()
                        .single()
                        .entries
                        .single()
                        .status,
                )
            } finally {
                external.close()
                library.close()
            }
        }

    @Test
    fun tracked_series_reads_its_own_episodes_before_external_requests_or_catalog_scans() =
        runTest {
            val order = mutableListOf<String>()
            val external =
                client {
                    order += "external"
                    awaitCancellation()
                }
            val library =
                client { request ->
                    order += request.url.encodedPath
                    assertEquals("/Shows/series/Episodes", request.url.encodedPath)
                    json(
                        """{"Items":[{"Id":"episode-1","Name":"第一集","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,"PremiereDate":"2026-09-04T00:00:00Z"}]}""",
                    )
                }
            try {
                val result =
                    repository(external, library)
                        .seriesCalendar(
                            FollowedSeries(42, "剧集", serverId = server.id, seriesItemId = "series"),
                            today = "2026-09-04",
                        ).getOrThrow()
                assertEquals("/Shows/series/Episodes", order.first())
                assertEquals(1, order.count { it == "/Shows/series/Episodes" })
                assertEquals(
                    LibraryStatus.Available,
                    result
                        .single()
                        .entries
                        .single()
                        .status,
                )
            } finally {
                external.close()
                library.close()
            }
        }

    @Test
    fun a_partial_detail_season_does_not_mark_other_seasons_missing() {
        val hint = SeriesCalendarLibraryHint(42, server, "series", listOf(episode))
        val otherSeason = libraryAiringSchedule(hint, "剧集").single().copy(seasonNumber = 2)
        assertEquals(
            LibraryStatus.Unknown,
            calendarPreviewDays(listOf(otherSeason), "2026-09-04", hint)
                .single()
                .entries
                .single()
                .status,
        )
    }

    @Test
    fun server_episodes_without_dates_do_not_get_invented_broadcast_dates() {
        val hint = SeriesCalendarLibraryHint(42, server, "series", listOf(episode.copy(premiereDate = null)))
        assertTrue(libraryAiringSchedule(hint, "剧集").isEmpty())
    }

    @Test
    fun an_external_timeout_without_library_or_cached_dates_is_a_retryable_failure() =
        runTest {
            val external = client { awaitCancellation() }
            val library = client { error("No series to resolve") }
            try {
                val result = repository(external, library).seriesCalendar(42, "剧集", today = "2026-09-04")
                assertTrue(result.isFailure)
            } finally {
                external.close()
                library.close()
            }
        }

    private fun repository(
        external: HttpClient,
        library: HttpClient,
        savedServer: SavedServer = server,
    ): AiringCalendarRepository {
        val settings = MapSettings()
        val registry = ServerRegistry(settings, TestSecureStore()).apply { addOrUpdate(savedServer) }
        val schedules = OfficialAiringScheduleCatalog(external, settings)
        return AiringCalendarRepository(
            EmbyRepository(library),
            registry,
            schedules,
            CalendarIdentityResolver(schedules, settings),
            CalendarFollowStore(settings),
        )
    }

    @Test
    fun tracking_artwork_uses_the_plex_poster_path_and_current_server_token() = runTest {
        val external = client { error("Artwork must not need an external schedule") }
        val library = client {
            json(
                """{"MediaContainer":{"Metadata":[{"ratingKey":"series","type":"show","title":"剧集","thumb":"/library/metadata/series/thumb/123"}]}}""",
            )
        }
        try {
            val urls = repository(external, library, server.copy(kind = MediaServerKind.Plex)).trackingPosterUrls(
                FollowedSeries(42, "剧集", serverId = server.id, seriesItemId = "series"),
            )
            assertTrue(urls.isNotEmpty())
            assertTrue(urls.first().contains("/photo/:/transcode"), urls.toString())
            assertTrue(urls.first().contains("X-Plex-Token=token"), urls.toString())
            assertFalse(urls.first().contains("/Items/series/Images/Primary"))
        } finally {
            external.close()
            library.close()
        }
    }

    @Test
    fun all_calendar_entry_points_only_request_the_server_schedule_feed() =
        runTest {
            val requests = mutableListOf<String>()
            val external =
                client { request ->
                    requests += request.url.encodedPath
                    // A failed refresh must keep the previously verified/bundled server publication.
                    json("{}")
                }
            val library = client { json("""{"Items":[]}""") }
            try {
                val settings = MapSettings()
                val schedules = OfficialAiringScheduleCatalog(external, settings)
                val follows = CalendarFollowStore(settings).apply { follow(FollowedSeries(272938, "师兄太稳健")) }
                val registry = ServerRegistry(settings, TestSecureStore())
                val repository =
                    AiringCalendarRepository(
                        EmbyRepository(library),
                        registry,
                        schedules,
                        CalendarIdentityResolver(schedules, settings),
                        follows,
                    )
                assertTrue(repository.calendar(today = "2026-08-25", forceRefresh = true).getOrThrow().isNotEmpty())
                assertTrue(repository.homeCalendar(today = "2026-08-25", forceRefresh = true).getOrThrow().isNotEmpty())
                assertTrue(repository.followedCalendar(today = "2026-08-25").getOrThrow().isNotEmpty())
                assertTrue(
                    repository
                        .seriesCalendar(
                            272938,
                            "师兄太稳健",
                            today = "2026-08-25",
                            forceRefresh = true,
                        ).getOrThrow()
                        .isNotEmpty(),
                )
                assertTrue(requests.isNotEmpty())
                assertTrue(requests.all { it == "/api/v1/calendar/schedules" }, requests.toString())
            } finally {
                external.close()
                library.close()
            }
        }

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                },
            ),
        ) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
