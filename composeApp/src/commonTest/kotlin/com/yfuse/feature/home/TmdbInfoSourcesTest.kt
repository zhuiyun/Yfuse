package com.yfuse.feature.home

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.MediaServerKind
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.parseWidgetMedia
import com.yfuse.core.widget.widgetDetailSeed
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbInfoSourcesTest {
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun aUrlTypedTvRankingCardStartsLibraryLookupWithoutATmdbIdentity() =
        runTest {
            val media = parseWidgetMedia(
                """[{"id":"source-42","type":"url","mediaType":"tv","title":"玉簟秋","releaseDate":"2026","rating":6.9}]""",
            ).single()
            var queries = 0
            val fixture = fixture(
                preferLibraryDetail = false,
                sourceContext = WidgetSourceContext("ranking", media),
                initialItem = media.widgetDetailSeed(),
                tmdbHandler = { throw IOException("Metadata unavailable") },
            ) { request ->
                if (request.url.encodedPath.endsWith("/Users/u/Items")) {
                    queries++
                    assertEquals("玉簟秋", request.url.parameters["SearchTerm"])
                    assertEquals("Series", request.url.parameters["IncludeItemTypes"])
                    assertNull(request.url.parameters["AnyProviderIdEquals"])
                    json("""{"Items":[{"Id":"series-42","Name":"玉簟秋","Type":"Series","ProductionYear":2026}]}""")
                } else {
                    json("""{"Items":[]}""")
                }
            }
            try {
                advanceUntilIdle()
                assertTrue(queries > 0)
                assertEquals("series-42", fixture.component.state.value.sources.single().itemId)
                assertTrue(fixture.component.state.value.playable)
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test fun aWidgetWithoutTmdbIdFindsLibrarySourcesWhileMetadataIsStillWaiting() =
        runTest {
            val metadata = CompletableDeferred<Unit>()
            val media = WidgetMedia("catalog-id", "Avengers: Endgame", mediaType = "movie", year = "2019")
            val fixture = fixture(
                sourceContext = WidgetSourceContext("catalog", media),
                initialItem = media.widgetDetailSeed(),
                tmdbHandler = {
                    metadata.await()
                    json("""{"results":[]}""")
                },
            ) { request ->
                assertEquals(media.title, request.url.parameters["SearchTerm"])
                assertNull(request.url.parameters["AnyProviderIdEquals"])
                assertEquals("Movie", request.url.parameters["IncludeItemTypes"])
                matchingMovie("primary")
            }
            try {
                runCurrent()
                assertTrue(fixture.component.state.value.loading)
                assertEquals(listOf("primary" to "movie-primary"), fixture.openedDetails)
                assertTrue(fixture.component.state.value.playable)
                assertEquals(0, fixture.playRequests)
                metadata.complete(Unit)
                advanceUntilIdle()
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertEquals(1, fixture.openedDetails.size)
            } finally {
                fixture.close()
            }
        }

    @Test fun failedTmdbMatchingDoesNotSuppressTitleLookupOrAcceptUnrelatedFilms() =
        runTest {
            val media = WidgetMedia("catalog-id", "Unrelated film", mediaType = "movie", year = "2019")
            var queries = 0
            val fixture = fixture(
                sourceContext = WidgetSourceContext("catalog", media),
                initialItem = media.widgetDetailSeed(),
                tmdbHandler = { throw IOException("Metadata unavailable") },
            ) { request ->
                queries++
                assertEquals(media.title, request.url.parameters["SearchTerm"])
                matchingMovie("primary")
            }
            try {
                advanceUntilIdle()
                assertTrue(queries > 0)
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertFalse(fixture.component.state.value.playable)
                assertNotNull(fixture.component.state.value.detailError)
                assertTrue(fixture.openedDetails.isEmpty())
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test fun live_source_detail_does_not_query_the_movie_catalog_or_block_its_playback_payload() =
        runTest {
            val media =
                WidgetMedia(
                    "live",
                    "Live room",
                    type = "url",
                    mediaType = "video",
                    videoUrl = "https://cdn.example/live.flv",
                )
            val fixture =
                fixture(sourceContext = WidgetSourceContext("local", media), initialItem = media.widgetDetailSeed()) {
                    error("Live sources must not search a movie library")
                }
            try {
                advanceUntilIdle()
                assertEquals(0, fixture.component.state.value.detail.item.id)
                assertFalse(fixture.component.state.value.loading)
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertEquals(media, fixture.component.widgetSourceContext?.media)
                assertNull(fixture.component.state.value.detailError)
                assertTrue(fixture.openedDetails.isEmpty())
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anExistingTitleOpensNativeDetailBeforeAnUnrelatedSlowServerFinishesWithoutPlaying() =
        runTest {
            val slowServer = CompletableDeferred<Unit>()
            val fixture =
                fixture(servers = listOf(server("slow"), server("fast"))) { request ->
                    assertEquals("tmdb.299534", request.url.parameters["AnyProviderIdEquals"])
                    if (request.url.host == "slow") slowServer.await()
                    matchingMovie(request.url.host)
                }
            try {
                runCurrent()
                assertTrue(fixture.component.state.value.sourcesLoading)
                assertEquals(listOf("fast" to "movie-fast"), fixture.openedDetails)
                assertEquals(
                    listOf("fast"),
                    fixture.component.state.value.sources
                        .map { it.serverId },
                )
                assertTrue(fixture.component.state.value.playable)
                assertEquals(0, fixture.playRequests)

                slowServer.complete(Unit)
                advanceUntilIdle()
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertEquals(
                    listOf("slow", "fast"),
                    fixture.component.state.value.sources
                        .map { it.serverId },
                )
                assertEquals(1, fixture.openedDetails.size)
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun anUnavailableServerIsNotReportedAsAnAbsentTitleAndExplicitRetryBypassesItsCooldown() =
        runTest {
            var healthy = false
            var calls = 0
            val fixture =
                fixture { _ ->
                    calls++
                    if (!healthy) throw IOException("Server unavailable")
                    matchingMovie("primary")
                }
            try {
                advanceUntilIdle()
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertFalse(fixture.component.state.value.playable)
                assertNotNull(fixture.component.state.value.sourcesError)
                assertTrue(fixture.openedDetails.isEmpty())
                val failedCalls = calls

                healthy = true
                fixture.component.retrySources()
                assertTrue(fixture.component.state.value.sourcesLoading)
                assertNull(fixture.component.state.value.sourcesError)
                advanceUntilIdle()
                assertTrue(calls > failedCalls, "Explicit retry must not reuse the failed server cooldown")
                assertEquals(listOf("primary" to "movie-primary"), fixture.openedDetails)
                assertNull(fixture.component.state.value.sourcesError)
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aCompletedNoMatchKeepsTheMetadataDetailAndTheOriginalComponentPlaybackPayload() =
        runTest {
            val context =
                WidgetSourceContext(
                    widgetId = "source-widget",
                    media =
                        WidgetMedia(
                            id = "source-film",
                            title = "复仇者联盟4",
                            link = "https://source.example/title/film",
                            headers = mapOf("Referer" to "https://source.example"),
                            children =
                                listOf(
                                    WidgetMedia("line-1", "线路一", videoUrl = "https://video.example/film.m3u8"),
                                ),
                        ),
                )
            val fixture = fixture(sourceContext = context) { json("""{"Items":[]}""") }
            try {
                advanceUntilIdle()
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertFalse(fixture.component.state.value.playable)
                assertNull(fixture.component.state.value.sourcesError)
                assertEquals(context, fixture.component.widgetSourceContext)
                assertTrue(fixture.openedDetails.isEmpty())
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun aLibraryChangeCancelsThePreviousLookupAndDoesNotOpenItsStaleResult() =
        runTest {
            val staleResponse = CompletableDeferred<Unit>()
            val fixture =
                fixture(servers = listOf(server("old"))) { request ->
                    if (request.url.host == "old") staleResponse.await()
                    matchingMovie(request.url.host)
                }
            try {
                runCurrent()
                fixture.registry.remove("old")
                fixture.registry.addOrUpdate(server("new"))
                runCurrent()
                staleResponse.complete(Unit)
                advanceUntilIdle()
                assertEquals(listOf("new" to "movie-new"), fixture.openedDetails)
                assertEquals(
                    listOf("new"),
                    fixture.component.state.value.sources
                        .map { it.serverId },
                )
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun leavingTheDetailWhileItIsLookingForSourcesDoesNotNavigateOrPlayLater() =
        runTest {
            val response = CompletableDeferred<Unit>()
            val fixture =
                fixture { _ ->
                    response.await()
                    matchingMovie("primary")
                }
            try {
                runCurrent()
                fixture.lifecycle.destroy()
                response.complete(Unit)
                advanceUntilIdle()
                assertTrue(fixture.openedDetails.isEmpty())
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.client.close()
            }
        }

    @Test
    fun jellyfinUsesTheSameProviderIdentityLookupAndNativeDetailRoute() =
        runTest {
            val fixture =
                fixture(servers = listOf(server("jellyfin").copy(kind = MediaServerKind.Jellyfin))) { request ->
                    assertEquals("tmdb.299534", request.url.parameters["AnyProviderIdEquals"])
                    assertEquals("Movie", request.url.parameters["IncludeItemTypes"])
                    matchingMovie("jellyfin")
                }
            try {
                advanceUntilIdle()
                assertEquals(listOf("jellyfin" to "movie-jellyfin"), fixture.openedDetails)
                assertTrue(fixture.component.state.value.playable)
                assertNull(fixture.component.state.value.sourcesError)
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    @Test
    fun ordinaryDiscoveryKeepsItsExistingMetadataRouteWhenSourcesAreFound() =
        runTest {
            val fixture = fixture(preferLibraryDetail = false) { matchingMovie("primary") }
            try {
                advanceUntilIdle()
                assertTrue(fixture.component.state.value.playable)
                assertFalse(fixture.component.state.value.sourcesLoading)
                assertTrue(fixture.openedDetails.isEmpty())
                assertEquals(0, fixture.playRequests)
            } finally {
                fixture.close()
            }
        }

    private fun TestScope.fixture(
        servers: List<SavedServer> = listOf(server("primary")),
        preferLibraryDetail: Boolean = true,
        sourceContext: WidgetSourceContext? = null,
        initialItem: TmdbItem = widgetDetailTestItem(),
        tmdbHandler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = {
            json("""{"id":299534,"title":"复仇者联盟4：终局之战"}""")
        },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val lifecycle = LifecycleRegistry().apply { resume() }
        val registry = testRegistry().apply { servers.forEach(::addOrUpdate) }
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        this.dispatcher = dispatcher
                        addHandler { request -> tmdbHandler(request) }
                    },
                ),
            ) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        val openedDetails = mutableListOf<Pair<String, String>>()
        var playRequests = 0
        val component =
            TmdbInfoComponent(
                componentContext = DefaultComponentContext(lifecycle),
                tmdb = TmdbRepository(client),
                emby = testRepo(dispatcher = dispatcher, handler = handler),
                registry = registry,
                item = initialItem,
                embyItemId = null,
                onBack = {},
                onPlayTarget = { _, _, _ -> playRequests++ },
                preferLibraryDetail = preferLibraryDetail,
                widgetSourceContext = sourceContext,
                onOpenLibraryDetail = { serverId, itemId -> openedDetails += serverId to itemId },
            )
        return Fixture(component, registry, lifecycle, client, openedDetails) { playRequests }
    }

    private class Fixture(
        val component: TmdbInfoComponent,
        val registry: ServerRegistry,
        val lifecycle: LifecycleRegistry,
        val client: HttpClient,
        val openedDetails: List<Pair<String, String>>,
        private val playCount: () -> Int,
    ) {
        val playRequests: Int get() = playCount()

        fun close() {
            lifecycle.destroy()
            client.close()
        }
    }

    private fun server(id: String) = SavedServer(id, "https://$id", id, "u", "User", "token")

    private fun MockRequestHandleScope.matchingMovie(serverId: String): HttpResponseData =
        json(
            """{"Items":[{"Id":"movie-$serverId","Name":"Avengers: Endgame","Type":"Movie",""" +
                """"ProductionYear":2019,"ProviderIds":{"Tmdb":"299534"},""" +
                """"MediaSources":[{"Id":"stream","Name":"4K","Container":"mkv"}]}]}""",
        )
}

internal fun widgetDetailTestItem() =
    TmdbItem(
        id = 299534,
        title = "复仇者联盟4：终局之战",
        overview = null,
        posterPath = null,
        backdropPath = null,
        year = "2019",
        mediaType = "movie",
        rating = null,
    )
