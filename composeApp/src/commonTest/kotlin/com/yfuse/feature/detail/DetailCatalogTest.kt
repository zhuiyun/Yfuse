package com.yfuse.feature.detail

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.sync.playback.PlaybackMutationKind
import com.yfuse.core.sync.playback.PlaybackSyncStore
import com.yfuse.core.sync.playback.PlaybackSyncTrigger
import com.yfuse.core.widget.InstalledWidget
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPersistence
import com.yfuse.core.widget.WidgetPlaybackRequest
import com.yfuse.core.widget.WidgetRepository
import com.yfuse.core.widget.WidgetRuntime
import com.yfuse.core.widget.WidgetSourceContext
import com.yfuse.core.widget.widgetDetailSeed
import com.yfuse.feature.home.TmdbInfoComponent
import com.yfuse.feature.json
import com.yfuse.feature.testRegistry
import com.yfuse.feature.testRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetailCatalogTest {
    @Test fun aCatalogPreviewCannotBypassTheEpisodeStreamResolverAndEachPlayRefreshesTheUrl() =
        runTest {
            val fixture = fixture(videoUrl = "https://cdn.example/two-minute-bonus.mp4")
            try {
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.selectCoordinate(2, 3)
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(1, fixture.runtime.calls.size)
                assertEquals(
                    "https://cdn.example/fresh.m3u8",
                    fixture.launches
                        .single()
                        .first.videoUrl,
                )
                fixture.runtime.response =
                    """[{"id":"resource","title":"线路一","videoUrl":"https://cdn.example/refreshed.m3u8"}]"""
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(2, fixture.runtime.calls.size)
                assertEquals(
                    "https://cdn.example/refreshed.m3u8",
                    fixture.launches
                        .last()
                        .first.videoUrl,
                )
                fixture.runtime.calls.forEach { request ->
                    val args = request.jsonObject
                    assertEquals(JsonPrimitive("42"), args["id"], "A resolved route ID is not the catalog ID")
                    assertEquals(JsonPrimitive(2), args["season"])
                    assertEquals(JsonPrimitive(3), args["episode"])
                }
            } finally {
                fixture.close()
            }
        }

    @Test fun aResolverFailureNeverFallsBackToTheCatalogPreview() =
        runTest {
            val fixture = fixture(videoUrl = "https://cdn.example/preview.mp4")
            try {
                fixture.runtime.response = "[]"
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(1, fixture.runtime.calls.size)
                assertTrue(fixture.launches.isEmpty())
                assertTrue(fixture.catalog.resources.value.error != null)
            } finally {
                fixture.close()
            }
        }

    @Test fun resolverRoutesForADifferentEpisodeCannotPlayAsTheSelectedEpisode() =
        runTest {
            val fixture = fixture()
            try {
                fixture.runtime.response =
                    """[{"id":"wrong","title":"第1集","season":1,"episode":1,"videoUrl":"https://cdn.example/wrong.m3u8"}]"""
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.selectCoordinate(1, 2)
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertTrue(fixture.launches.isEmpty())
                assertTrue(fixture.catalog.resources.value.error != null)
            } finally {
                fixture.close()
            }
        }

    @Test fun choosingAmongFreshResolvedRoutesDoesNotResolveAgainUntilTheNextPlayback() =
        runTest {
            val fixture = fixture(videoUrl = "https://cdn.example/preview.mp4")
            try {
                fixture.runtime.response = """[{"id":"hd","title":"HD","videoUrl":"https://cdn.example/hd.m3u8"},
                    {"id":"fhd","title":"FHD","videoUrl":"https://cdn.example/fhd.m3u8"}]"""
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertTrue(fixture.launches.isEmpty())
                fixture.catalog.selectRoute("fhd")
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(1, fixture.runtime.calls.size)
                assertEquals(
                    "https://cdn.example/fhd.m3u8",
                    fixture.launches
                        .single()
                        .first.videoUrl,
                )
            } finally {
                fixture.close()
            }
        }

    @Test fun aDirectVideoWithoutAResolverStillPlaysDirectly() =
        runTest {
            val fixture = fixture(videoUrl = "https://cdn.example/full-episode.m3u8", stream = false)
            try {
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertTrue(fixture.runtime.calls.isEmpty())
                assertEquals(
                    "https://cdn.example/full-episode.m3u8",
                    fixture.launches
                        .single()
                        .first.videoUrl,
                )
            } finally {
                fixture.close()
            }
        }

    @Test fun restoresTheLatestEpisodeAndResolvesFreshResourcesOnlyAfterPlay() =
        runTest {
            val fixture = fixture()
            try {
                fixture.writeProgress("tmdb:42/s2e3", 123_000L)
                fixture.start()
                advanceUntilIdle()
                assertEquals("Series", fixture.state.detail?.type)
                assertEquals(3, fixture.state.playTarget?.episodeNumber)
                assertEquals(2, fixture.state.playTarget?.seasonNumber)
                assertEquals(123_000L * 10_000, fixture.state.playPositionTicks)
                assertTrue(fixture.runtime.calls.isEmpty(), "Opening details must not consume signed stream URLs")
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                val launch = fixture.launches.single()
                assertEquals("tmdb:42/s2e3", launch.second.identity.mediaKey)
                assertEquals(123_000L, launch.second.positionMs)
                val args =
                    fixture.runtime.calls
                        .single()
                        .jsonObject
                assertEquals(JsonPrimitive(2), args["season"])
                assertEquals(JsonPrimitive(3), args["episode"])
            } finally {
                fixture.close()
            }
        }

    @Test fun progressUpdatesAfterPlayerReturnAndFromStartBeginsANewGeneration() =
        runTest {
            val fixture = fixture()
            try {
                fixture.start()
                advanceUntilIdle()
                fixture.writeProgress("tmdb:42/s1e1", 240_000L)
                runCurrent()
                assertEquals(240_000L * 10_000, fixture.state.playPositionTicks)
                assertEquals(
                    240_000L * 10_000,
                    fixture.state.episodes
                        .first()
                        .resumePositionTicks,
                )
                fixture.catalog.handle(DetailIntent.PlayFromStart, fixture.state)
                advanceUntilIdle()
                assertEquals(
                    0L,
                    fixture.launches
                        .single()
                        .second.positionMs,
                )
                val record = requireNotNull(fixture.progress.find("tmdb:42/s1e1")).document.state
                assertEquals(0L, record.positionMs)
                assertTrue(record.progressEpoch > 0)
            } finally {
                fixture.close()
            }
        }

    @Test fun changingEpisodesCancelsAPendingStreamAndCannotLaunchTheOldEpisode() =
        runTest {
            val fixture = fixture()
            try {
                fixture.start()
                advanceUntilIdle()
                fixture.runtime.block = CompletableDeferred()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                runCurrent()
                assertTrue(fixture.catalog.resources.value.loading)
                fixture.catalog.handle(DetailIntent.SelectEpisode("tmdb:42/s1e2", 0L), fixture.state)
                fixture.runtime.block?.complete(Unit)
                advanceUntilIdle()
                assertTrue(fixture.launches.isEmpty())
                assertEquals(2, fixture.state.playTarget?.episodeNumber)
                fixture.runtime.block = null
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(
                    "tmdb:42/s1e2",
                    fixture.launches
                        .single()
                        .second.identity.mediaKey,
                )
            } finally {
                fixture.close()
            }
        }

    @Test fun anEmptyResolverStopsWithAnErrorAndDoesNotLoopOrPlay() =
        runTest {
            val fixture = fixture()
            try {
                fixture.runtime.response = "[]"
                fixture.start()
                advanceUntilIdle()
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(1, fixture.runtime.calls.size)
                assertTrue(fixture.launches.isEmpty())
                assertFalse(fixture.state.resolvingPlay)
                assertTrue(fixture.catalog.resources.value.error != null)
            } finally {
                fixture.close()
            }
        }

    @Test fun switchingFromALibraryCopyRetainsItsResumePointForTheComponentSource() =
        runTest {
            val fixture = fixture()
            try {
                fixture.start()
                advanceUntilIdle()
                val server =
                    com.yfuse.core.model
                        .SavedServer("library", "https://library", "Library", "u", "User", "token")
                val target = requireNotNull(fixture.state.playTarget).copy(id = "library-e1")
                val source = requireNotNull(fixture.state.detail).copy(id = "library-series")
                fixture.progress.updatePlayback(
                    "emby:library-e1",
                    listOf("emby:library-e1"),
                    345_000L,
                    1_000_000L,
                    false,
                    "library-session",
                    server.id,
                    "library-e1",
                    PlaybackMutationKind.AutoProgress,
                    PlaybackSyncTrigger.Stop,
                )
                fixture.state =
                    fixture.state.copy(
                        server = server,
                        playServer = server,
                        detail = source,
                        playSourceDetail = source,
                        playTarget = target,
                        catalogPlayback = false,
                    )
                fixture.catalog.syncLibrarySelection(fixture.state)
                fixture.catalog.selectRoute(
                    fixture.catalog.resources.value.routes
                        .first()
                        .id,
                )
                assertTrue(fixture.state.catalogPlayback)
                assertEquals(345_000L * 10_000L, fixture.state.playPositionTicks)
                fixture.catalog.handle(DetailIntent.Play, fixture.state)
                advanceUntilIdle()
                assertEquals(
                    345_000L,
                    fixture.launches
                        .single()
                        .second.positionMs,
                )
            } finally {
                fixture.close()
            }
        }

    private suspend fun TestScope.fixture(
        videoUrl: String = "",
        stream: Boolean = true,
    ): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val owner = CoroutineScope(SupervisorJob() + dispatcher)
        val lifecycle = LifecycleRegistry().apply { resume() }
        val client =
            HttpClient(
                MockEngine(
                    MockEngineConfig().apply {
                        this.dispatcher = dispatcher
                        addHandler { request ->
                            if (request.url.encodedPath.contains("/season/")) {
                                val season =
                                    request.url.encodedPath
                                        .substringAfterLast('/')
                                        .toInt()
                                json(
                                    """{"episodes":[
                                    {"episode_number":1,"season_number":$season},
                                    {"episode_number":2,"season_number":$season},
                                    {"episode_number":3,"season_number":$season}]}""",
                                )
                            } else {
                                json(
                                    """{"id":42,"name":"测试剧","number_of_seasons":2,
                                    "seasons":[{"season_number":1},{"season_number":2}]}""",
                                )
                            }
                        }
                    },
                ),
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val runtime = Runtime()
        val persistence =
            object : WidgetPersistence {
                override suspend fun read(): String? = null

                override suspend fun write(value: String) = Unit
            }
        val widgets = WidgetRepository(persistence, runtime, owner, { "id" }, { it }, { 0L })
        val resolver =
            if (stream) {
                """,{"id":"loadResource","title":"资源","type":"stream","functionName":"stream"}"""
            } else {
                ""
            }
        val metadata = """{"id":"catalog","title":"测试组件","modules":[
                {"id":"list","title":"List","functionName":"list"}$resolver]}"""
        widgets.install(widgets.inspect(metadata, "file:catalog.js"))
        val media =
            WidgetMedia(
                "42",
                "测试剧",
                type = "tmdb",
                mediaType = "tv",
                tmdbId = "42",
                videoUrl = videoUrl,
            )
        val source = WidgetSourceContext("catalog", media)
        val tmdb = TmdbRepository(client)
        val info =
            TmdbInfoComponent(
                DefaultComponentContext(lifecycle),
                tmdb,
                testRepo(dispatcher = dispatcher) { error("No library server should be queried") },
                testRegistry(),
                media.widgetDetailSeed(),
                null,
                {},
                { _, _, _ -> },
                widgetSourceContext = source,
            )
        val progress = PlaybackSyncStore(MapSettings()) { 1000L }
        val launches = mutableListOf<Pair<WidgetMedia, WidgetPlaybackRequest>>()
        val catalog =
            DetailCatalog(info, source, tmdb, widgets, progress) { item, request ->
                launches +=
                    item to request
            }
        return Fixture(owner, lifecycle, client, runtime, progress, catalog, launches)
    }

    private class Runtime : WidgetRuntime {
        val calls = mutableListOf<JsonElement>()
        var block: CompletableDeferred<Unit>? = null
        var response = """[{"id":"resource","title":"线路一","videoUrl":"https://cdn.example/fresh.m3u8"}]"""

        override suspend fun metadata(source: String): String = source

        override suspend fun invoke(
            widget: InstalledWidget,
            function: String,
            argument: JsonElement,
            timeoutSeconds: Int,
        ): String {
            calls += argument
            block?.await()
            return response
        }
    }

    private class Fixture(
        val owner: CoroutineScope,
        val lifecycle: LifecycleRegistry,
        val client: HttpClient,
        val runtime: Runtime,
        val progress: PlaybackSyncStore,
        val catalog: DetailCatalog,
        val launches: List<Pair<WidgetMedia, WidgetPlaybackRequest>>,
    ) {
        var state = catalog.initialState()

        fun start() {
            catalog.attach(owner, {
                state
            }, { msg -> state = with(DetailReducer) { state.reduce(msg) } }, { _, _ -> error("No library") })
        }

        fun writeProgress(
            key: String,
            position: Long,
        ) {
            progress.updatePlayback(
                key,
                listOf(key),
                position,
                1_000_000L,
                false,
                "session",
                null,
                null,
                PlaybackMutationKind.AutoProgress,
                PlaybackSyncTrigger.Stop,
            )
        }

        fun close() {
            owner.cancel()
            lifecycle.destroy()
            client.close()
            Dispatchers.resetMain()
        }
    }
}
