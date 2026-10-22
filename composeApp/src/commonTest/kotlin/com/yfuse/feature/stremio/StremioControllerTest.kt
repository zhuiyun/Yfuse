package com.yfuse.feature.stremio

import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.stremio.StremioClient
import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.core.stremio.StremioSource
import com.yfuse.core.stremio.StremioStream
import com.yfuse.core.stremio.fixtureManifest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioControllerTest {
    @Test
    fun changing_filter_cancels_old_catalog_response_before_it_can_reappear() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            HttpClient(
                mockEngine {
                    started.complete(Unit)
                    release.await()
                    respond("""{"metas":[{"id":"old","type":"movie","name":"Old"}]}""")
                },
            ).use { http ->
                val controller = controller(http)
                controller.selectCatalog(
                    controller.state.value.catalogs
                        .single(),
                )
                started.await()
                controller.filter("search", "new")
                release.complete(Unit)
                advanceUntilIdle()
                assertTrue(
                    controller.state.value.items
                        .isEmpty(),
                )
                assertEquals("new", controller.state.value.extras["search"])
                assertFalse(controller.state.value.loading)
            }
        }

    @Test
    fun paging_uses_raw_offset_and_stops_when_plugin_repeats_the_same_page() =
        runTest {
            val paths = mutableListOf<String>()
            HttpClient(
                mockEngine { request ->
                    paths += request.url.encodedPath
                    respond(
                        """{"metas":[{"id":"tt1","type":"movie","name":"One"},{"broken":true}]}""",
                    )
                },
            ).use { http ->
                val controller = controller(http)
                controller.selectCatalog(
                    controller.state.value.catalogs
                        .single(),
                )
                controller.state.first { !it.loading && it.items.isNotEmpty() }
                assertEquals(2, controller.state.value.nextOffset)
                assertEquals(1, controller.state.value.items.size)
                controller.browse(more = true)
                controller.state.first { !it.loading }
                assertTrue(paths.last().contains("skip=2"))
                assertFalse(controller.state.value.canLoadMore)
            }
        }

    @Test
    fun back_cancels_subtitle_preparation_and_never_launches_later() =
        runTest {
            val subtitleStarted = CompletableDeferred<Unit>()
            HttpClient(
                mockEngine { request ->
                    when {
                        "/meta/" in request.url.encodedPath ->
                            respond(
                                """{"meta":{"id":"tt1","type":"movie","name":"One"}}""",
                            )
                        "/stream/" in request.url.encodedPath ->
                            respond(
                                """{"streams":[{"url":"https://cdn.example/movie.m3u8"}]}""",
                            )
                        else -> {
                            subtitleStarted.complete(Unit)
                            delay(20_000)
                            respond("""{"subtitles":[]}""")
                        }
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("tt1", "movie", "One"))
                controller.state.first { !it.loading }
                controller.play(
                    controller.state.value.sources
                        .single(),
                )
                subtitleStarted.await()
                assertTrue(controller.back())
                advanceUntilIdle()
                assertNull(controller.state.value.launch)
                assertNull(controller.state.value.video)
                assertNull(controller.state.value.detail)
            }
        }

    @Test
    fun subtitle_timeout_still_launches_the_selected_video() =
        runTest {
            HttpClient(
                mockEngine { request ->
                    when {
                        "/meta/" in request.url.encodedPath ->
                            respond(
                                """{"meta":{"id":"tt1","type":"movie","name":"One"}}""",
                            )
                        "/stream/" in request.url.encodedPath ->
                            respond(
                                """{"streams":[{"url":"https://cdn.example/movie.m3u8"}]}""",
                            )
                        else -> {
                            delay(20_000)
                            respond("""{"subtitles":[]}""")
                        }
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("tt1", "movie", "One"))
                controller.state.first { !it.loading }
                controller.play(
                    controller.state.value.sources
                        .single(),
                )
                val launched = controller.state.first { it.launch != null }
                assertEquals("https://cdn.example/movie.m3u8", launched.launch?.url)
                controller.consumeLaunch()
                assertNull(controller.state.value.launch)
            }
        }

    @Test
    fun unsupported_source_shows_reason_without_starting_playback() =
        runTest {
            HttpClient(mockEngine { error("No request needed") }).use { http ->
                val controller = controller(http)
                controller.play(StremioSource("Torrent", StremioStream(infoHash = "abc")))
                assertTrue(
                    controller.state.value.message
                        .orEmpty()
                        .contains("BT"),
                )
                assertNull(controller.state.value.launch)
            }
        }

    @Test
    fun movie_opens_detail_before_automatic_sources_and_returns_directly_to_catalog() =
        runTest {
            val metaStarted = CompletableDeferred<Unit>()
            val releaseMeta = CompletableDeferred<Unit>()
            val sourcesStarted = CompletableDeferred<Unit>()
            val releaseSources = CompletableDeferred<Unit>()
            HttpClient(
                mockEngine { request ->
                    when {
                        "/catalog/" in request.url.encodedPath ->
                            respond(
                                """{"metas":[{"id":"tt1","type":"movie","name":"Preview"}]}""",
                            )
                        "/meta/" in request.url.encodedPath -> {
                            metaStarted.complete(Unit)
                            releaseMeta.await()
                            respond("""{"meta":{"id":"tt1","type":"movie","name":"Full detail"}}""")
                        }
                        "/stream/" in request.url.encodedPath -> {
                            sourcesStarted.complete(Unit)
                            releaseSources.await()
                            respond("""{"streams":[{"url":"https://cdn.example/automatic.m3u8"}]}""")
                        }
                        else -> error("Opening detail must not request subtitles or start playback")
                    }
                },
            ).use { http ->
                val controller = controller(http)
                val catalog =
                    controller.state.value.catalogs
                        .single()
                controller.selectCatalog(catalog)
                val items = controller.state.first { !it.loading }.items
                controller.open(items.single())
                assertEquals(
                    "Preview",
                    controller.state.value.detail
                        ?.name,
                )
                metaStarted.await()
                assertFalse(sourcesStarted.isCompleted)
                releaseMeta.complete(Unit)
                sourcesStarted.await()
                assertEquals(
                    "Full detail",
                    controller.state.value.detail
                        ?.name,
                )
                assertEquals(
                    "tt1",
                    controller.state.value.video
                        ?.id,
                )
                assertTrue(controller.state.value.loading)
                assertNull(controller.state.value.launch)
                releaseSources.complete(Unit)
                val loaded = controller.state.first { !it.loading }
                assertEquals(
                    "https://cdn.example/automatic.m3u8",
                    loaded.sources
                        .single()
                        .stream.url,
                )
                assertNull(loaded.launch)
                assertTrue(controller.back())
                assertNull(controller.state.value.detail)
                assertNull(controller.state.value.video)
                assertEquals(catalog, controller.state.value.catalog)
                assertEquals(items, controller.state.value.items)
            }
        }

    @Test
    fun failed_metadata_still_looks_up_movie_sources_and_source_retry_recovers() =
        runTest {
            var streamCalls = 0
            HttpClient(
                mockEngine { request ->
                    if ("/meta/" in request.url.encodedPath) error("Details unavailable")
                    streamCalls++
                    if (streamCalls == 1) error("Source plugin unavailable")
                    respond("""{"streams":[{"url":"https://cdn.example/retry.m3u8"}]}""")
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("tt1", "movie", "Catalog preview"))
                val failed = controller.state.first { !it.loading }
                assertEquals("Catalog preview", failed.detail?.name)
                assertEquals("tt1", failed.video?.id)
                assertTrue(failed.failures.isNotEmpty())
                assertTrue(failed.sources.isEmpty())
                assertNull(failed.launch)
                controller.selectVideo(checkNotNull(failed.video))
                val retried = controller.state.first { !it.loading }
                assertEquals(2, streamCalls)
                assertEquals(
                    "https://cdn.example/retry.m3u8",
                    retried.sources
                        .single()
                        .stream.url,
                )
                assertTrue(retried.failures.isEmpty())
                assertNull(retried.launch)
            }
        }

    @Test
    fun series_waits_for_episode_choice_and_late_episode_response_cannot_replace_new_selection() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val streams = mutableListOf<String>()
            HttpClient(
                mockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        "/meta/" in path -> respond(seriesMeta)
                        "episode-one" in path -> {
                            streams += "episode-one"
                            firstStarted.complete(Unit)
                            withContext(NonCancellable) { releaseFirst.await() }
                            respond("""{"streams":[{"url":"https://cdn.example/old.m3u8"}]}""")
                        }
                        else -> {
                            streams += "episode-two"
                            respond("""{"streams":[{"url":"https://cdn.example/current.m3u8"}]}""")
                        }
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("show", "series", "Show"))
                val detail = controller.state.first { !it.loading }
                assertNull(detail.video)
                assertTrue(streams.isEmpty())
                val episodes = checkNotNull(detail.detail).videos
                controller.selectVideo(episodes[0])
                firstStarted.await()
                controller.selectVideo(episodes[1])
                val current = controller.state.first { !it.loading }
                assertEquals("episode-two", current.video?.id)
                assertEquals(
                    "https://cdn.example/current.m3u8",
                    current.sources
                        .single()
                        .stream.url,
                )
                releaseFirst.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    "episode-two",
                    controller.state.value.video
                        ?.id,
                )
                assertEquals(
                    "https://cdn.example/current.m3u8",
                    controller.state.value.sources
                        .single()
                        .stream.url,
                )
                assertNull(controller.state.value.launch)
                assertTrue(controller.back())
                assertEquals(
                    "show",
                    controller.state.value.detail
                        ?.id,
                )
                assertNull(controller.state.value.video)
                assertTrue(
                    controller.state.value.sources
                        .isEmpty(),
                )
                assertTrue(controller.back())
                assertNull(controller.state.value.detail)
            }
        }

    @Test
    fun leaving_detail_during_automatic_source_lookup_discards_late_results() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            HttpClient(
                mockEngine { request ->
                    if ("/meta/" in request.url.encodedPath) {
                        respond("""{"meta":{"id":"tt1","type":"movie","name":"One"}}""")
                    } else {
                        started.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        respond("""{"streams":[{"url":"https://cdn.example/late.m3u8"}]}""")
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("tt1", "movie", "One"))
                started.await()
                assertTrue(controller.back())
                release.complete(Unit)
                advanceUntilIdle()
                assertNull(controller.state.value.detail)
                assertNull(controller.state.value.video)
                assertNull(controller.state.value.launch)
                assertTrue(
                    controller.state.value.sources
                        .isEmpty(),
                )
                assertFalse(controller.state.value.loading)
            }
        }

    @Test
    fun opening_another_title_discards_late_metadata_before_it_can_start_sources() =
        runTest {
            val oldStarted = CompletableDeferred<Unit>()
            val releaseOld = CompletableDeferred<Unit>()
            val sourcePaths = mutableListOf<String>()
            HttpClient(
                mockEngine { request ->
                    val path = request.url.encodedPath
                    when {
                        "/meta/movie/old" in path -> {
                            oldStarted.complete(Unit)
                            withContext(NonCancellable) { releaseOld.await() }
                            respond("""{"meta":{"id":"old","type":"movie","name":"Old detail"}}""")
                        }
                        "/meta/" in path -> respond("""{"meta":{"id":"new","type":"movie","name":"New detail"}}""")
                        else -> {
                            sourcePaths += path
                            respond("""{"streams":[{"url":"https://cdn.example/new.m3u8"}]}""")
                        }
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("old", "movie", "Old preview"))
                oldStarted.await()
                controller.open(StremioMeta("new", "movie", "New preview"))
                controller.state.first { !it.loading }
                releaseOld.complete(Unit)
                advanceUntilIdle()
                assertEquals(
                    "new",
                    controller.state.value.detail
                        ?.id,
                )
                assertEquals(
                    "new",
                    controller.state.value.video
                        ?.id,
                )
                assertEquals(1, sourcePaths.size)
                assertTrue(sourcePaths.single().endsWith("/new.json"))
                assertNull(controller.state.value.launch)
            }
        }

    @Test
    fun refreshing_series_detail_preserves_selected_episode_and_reloads_its_sources() =
        runTest {
            var streamCalls = 0
            HttpClient(
                mockEngine { request ->
                    if ("/meta/" in request.url.encodedPath) {
                        respond(seriesMeta)
                    } else {
                        streamCalls++
                        respond("""{"streams":[{"url":"https://cdn.example/episode.m3u8"}]}""")
                    }
                },
            ).use { http ->
                val controller = controller(http)
                controller.open(StremioMeta("show", "series", "Show"))
                val detail = controller.state.first { !it.loading }.detail!!
                controller.selectVideo(detail.videos[1])
                controller.state.first { !it.loading }
                controller.detail(detail)
                val refreshed = controller.state.first { !it.loading }
                assertEquals("episode-two", refreshed.video?.id)
                assertEquals(2, streamCalls)
                assertNull(refreshed.launch)
            }
        }

    private val seriesMeta =
        """{"meta":{"id":"show","type":"series","name":"Show","videos":[{"id":"episode-one","title":"One","season":1,"episode":1},{"id":"episode-two","title":"Two","season":1,"episode":2}]}}"""

    private fun TestScope.mockEngine(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): MockEngine =
        MockEngine(
            MockEngineConfig().apply {
                // Share the controller's virtual clock: its subtitle timeout must not overtake
                // an HTTP handler still waiting to be scheduled on a real worker thread.
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        )

    private suspend fun TestScope.controller(http: HttpClient): StremioController {
        val repository = StremioRepository(StremioClient(http), TestSecureStore(), "adult")
        repository.load()
        repository.install(
            "https://plugin.example/manifest.json",
            fixtureManifest().copy(types = listOf("movie", "series", "tv")),
        )
        return StremioController(repository, this).also {
            it.load()
            it.state.first { state -> state.ready && !state.busy }
        }
    }
}
