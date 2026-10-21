package com.yfuse.feature.stremio

import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.stremio.StremioClient
import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioRepository
import com.yfuse.core.stremio.StremioSource
import com.yfuse.core.stremio.StremioStream
import com.yfuse.core.stremio.StremioVideo
import com.yfuse.core.stremio.fixtureManifest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
                controller.selectVideo(StremioVideo("tt1"))
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
                assertEquals(
                    "tt1",
                    controller.state.value.detail
                        ?.id,
                )
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
                controller.selectVideo(StremioVideo("tt1"))
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
        repository.install("https://plugin.example/manifest.json", fixtureManifest())
        return StremioController(repository, this).also {
            it.load()
            it.state.first { state -> state.ready && !state.busy }
        }
    }
}
