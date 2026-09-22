package com.yfuse.core.data

import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.homeRoutes
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbyHomeServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun library_previews_finish_before_slow_optional_metadata_exhausts_the_budget() =
        runTest {
            val ids = (1..10).map { "library-$it" }
            val views =
                ids.joinToString(prefix = "{\"Items\":[", postfix = "]}") {
                    """{"Id":"$it","Name":"$it","CollectionType":"movies"}"""
                }
            var active = 0
            var peak = 0
            val requested = mutableSetOf<String>()
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/Views")) {
                        json(views)
                    } else {
                        active++
                        peak = maxOf(peak, active)
                        try {
                            // Each request is healthy, but later sections wait longer than one budget.
                            delay(6_000)
                            if (request.url.encodedPath.endsWith("/Latest")) {
                                requested += requireNotNull(request.url.parameters["ParentId"])
                            }
                            homeRoutes(request)
                        } finally {
                            active--
                        }
                    }
                }
            try {
                val content = service(client).homeContent(server).getOrThrow()
                val rows = content.rows.filter { it.libraryId in ids }
                assertEquals(ids, rows.map { it.libraryId })
                assertEquals(ids.toSet(), requested)
                assertTrue(rows.all { it.items.isNotEmpty() && !it.loadFailed })
                assertTrue(peak <= 4)
                assertTrue(testScheduler.currentTime > 15_000)
                assertTrue(testScheduler.currentTime <= 30_000)
            } finally {
                client.close()
            }
        }

    @Test
    fun directory_and_preview_are_published_while_counts_are_still_pending() =
        runTest {
            val updates = mutableListOf<HomeContent>()
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/Counts") || request.url.parameters["Limit"] == "0") {
                        awaitCancellation()
                    }
                    homeRoutes(request)
                }
            try {
                val load = async { service(client).homeContent(server, onProgress = { updates += it }) }
                runCurrent()
                assertFalse(load.isCompleted)
                assertTrue(updates.first().rows.any { it.libraryId == "lib1" })
                assertTrue(updates.first().rows.all { it.items.isEmpty() })
                assertTrue(
                    updates
                        .last()
                        .rows
                        .first { it.libraryId == "lib1" }
                        .items
                        .isNotEmpty(),
                )
                assertEquals(0L, testScheduler.currentTime)
                load.await().getOrThrow()
                assertTrue(testScheduler.currentTime <= 30_000)
            } finally {
                client.close()
            }
        }

    @Test
    fun many_stalled_sections_have_one_budget_and_keep_every_browse_entry() =
        runTest {
            val ids = (1..20).map { "library-$it" }
            val views = ids.joinToString(prefix = "{\"Items\":[", postfix = "]}") { """{"Id":"$it","Name":"$it"}""" }
            val updates = mutableListOf<HomeContent>()
            var active = 0
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/Views")) {
                        json(views)
                    } else {
                        active++
                        try {
                            awaitCancellation()
                        } finally {
                            active--
                        }
                    }
                }
            try {
                val result = service(client).homeContent(server, onProgress = { updates += it }).getOrThrow()
                assertEquals(
                    ids,
                    updates
                        .first()
                        .rows
                        .takeLast(20)
                        .map { it.libraryId },
                )
                assertTrue(result.rows.takeLast(20).all { it.loadFailed })
                assertEquals(30_000L, testScheduler.currentTime)
                assertEquals(0, active)
            } finally {
                client.close()
            }
        }

    @Test
    fun cancelling_enrichment_stops_requests_and_preserves_cached_previews() =
        runTest {
            var stall = false
            var active = 0
            val client =
                client { request ->
                    if (stall && !request.url.encodedPath.endsWith("/Views")) {
                        active++
                        try {
                            awaitCancellation()
                        } finally {
                            active--
                        }
                    }
                    homeRoutes(request)
                }
            try {
                val service = service(client)
                val cached = service.homeContent(server).getOrThrow()
                stall = true
                val updates = mutableListOf<HomeContent>()
                val load = async { service.homeContent(server, cached) { updates += it } }
                runCurrent()
                assertEquals(cached.rows, updates.first().rows)
                assertEquals(cached.rows, updates.last().rows)
                assertTrue(active > 0)
                load.cancelAndJoin()
                val updateCount = updates.size
                advanceUntilIdle()
                assertTrue(load.isCancelled)
                assertEquals(0, active)
                assertEquals(updateCount, updates.size)
            } finally {
                client.close()
            }
        }

    @Test
    fun failed_preview_keeps_its_library_entry_and_recovers_on_refresh() =
        runTest {
            var stall = true
            val views = """{"Items":[{"Id":"slow","Name":"Slow"},{"Id":"fast","Name":"Fast"}]}"""
            val client =
                client { request ->
                    if (stall &&
                        request.url.encodedPath.endsWith("/Latest") &&
                        request.url.parameters["ParentId"] == "slow"
                    ) {
                        awaitCancellation()
                    }
                    homeRoutes(request, views = views)
                }
            try {
                val service = service(client)
                val partial = service.homeContent(server).getOrThrow()
                assertEquals(listOf("slow", "fast"), partial.rows.takeLast(2).map { it.libraryId })
                assertTrue(partial.rows.first { it.libraryId == "slow" }.loadFailed)
                assertFalse(partial.rows.first { it.libraryId == "fast" }.loadFailed)
                assertTrue(
                    partial.rows
                        .first { it.libraryId == "fast" }
                        .items
                        .isNotEmpty(),
                )

                stall = false
                val recovered = service.homeContent(server).getOrThrow()
                assertTrue(recovered.rows.takeLast(2).all { !it.loadFailed && it.items.isNotEmpty() })
            } finally {
                client.close()
            }
        }

    @Test
    fun stalled_directory_returns_a_failure_instead_of_cancelling_the_store_load() =
        runTest {
            var stall = true
            val client =
                client { request ->
                    if (stall) awaitCancellation()
                    homeRoutes(request)
                }
            try {
                val service = service(client)
                assertTrue(service.homeContent(server).isFailure)
                stall = false
                assertTrue(
                    service
                        .homeContent(server)
                        .getOrThrow()
                        .rows
                        .any { it.libraryId == "lib1" },
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun leaving_the_page_still_cancels_the_request() =
        runTest {
            val started = CompletableDeferred<Unit>()
            val client =
                client {
                    started.complete(Unit)
                    awaitCancellation()
                }
            try {
                val load = async { service(client).homeContent(server) }
                started.await()
                load.cancelAndJoin()
                assertTrue(load.isCancelled)
            } finally {
                client.close()
            }
        }

    private fun TestScope.client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient =
        createEmbyClient(
            appVersion = "test",
            engine =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(testScheduler)
                        addHandler(handler)
                    },
                ),
            timeouts = null,
        )

    private fun service(client: HttpClient) =
        EmbyHomeService(
            client = client,
            libraryService = EmbyLibraryService(client),
            browseService = EmbyBrowseService(client),
        )
}
