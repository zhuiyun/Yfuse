package com.yfuse.core.data

import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmdbDomesticAiringCalendarSourceTest {
    @Test
    fun domestic_discovery_uses_cn_first_pages_and_preserves_real_dates_without_claiming_official_authority() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/3/discover/tv" -> json("""{"results":[{"id":7,"name":"国产新剧","origin_country":["CN"]}]}""")
                        "/3/tv/7" -> json(show(7))
                        "/3/tv/7/season/1" ->
                            json("""{"episodes":[{"episode_number":1,"name":"未公布日期"}]}""")
                        else -> error("Unexpected endpoint: ${request.url.encodedPath}")
                    }
                }
            try {
                val result = TmdbDomesticAiringCalendarSource(client).calendar("2026-11-01", "2026-11-12", false)

                assertTrue(result.complete)
                assertEquals(listOf(12, 13), result.episodes.map { it.episodeNumber })
                assertEquals(listOf("2026-11-04", "2026-11-06"), result.episodes.map { it.airDate })
                assertTrue(result.episodes.all { it.origin == ShowOrigin.Domestic })
                assertTrue(result.episodes.all { it.scheduleAuthority == AiringScheduleAuthority.Tmdb })
                assertTrue(result.episodes.all { it.scheduleProvider == "TMDB" && it.scheduleEvidence.isEmpty() })
                assertTrue(result.episodes.all { it.airTime == null && it.releaseAtUtc == null })
                val discovery = requests.filter { it.url.encodedPath == "/3/discover/tv" }
                assertEquals(2, discovery.size)
                assertTrue(discovery.all { it.url.parameters["with_origin_country"] == "CN" })
                assertTrue(discovery.all { it.url.parameters["page"] in listOf(null, "1") })
                assertEquals(1, requests.count { it.url.encodedPath == "/3/tv/7" })
                assertEquals(1, requests.count { it.url.encodedPath == "/3/tv/7/season/1" })
            } finally {
                client.close()
            }
        }

    @Test
    fun a_foreign_detail_cannot_become_domestic_just_because_discovery_returned_it() =
        runTest {
            val client =
                client { request ->
                    when (request.url.encodedPath) {
                        "/3/discover/tv" -> json("""{"results":[{"id":7,"name":"中文译名"}]}""")
                        "/3/tv/7" -> json(show(7, country = "US"))
                        else -> error("Foreign identity must not expand its season")
                    }
                }
            try {
                val result = TmdbDomesticAiringCalendarSource(client).calendar("2026-11-01", "2026-11-12", false)
                assertTrue(result.episodes.isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun large_discovery_lists_are_deduplicated_and_bound_detail_work_and_parallelism() =
        runTest {
            val detailIds = mutableListOf<Int>()
            var active = 0
            var peak = 0
            val client =
                client { request ->
                    val path = request.url.encodedPath
                    when {
                        path == "/3/discover/tv" ->
                            json("""{"results":[${(1..40).joinToString { """{"id":$it,"name":"国产$it"}""" }}]}""")
                        Regex("/3/tv/\\d+").matches(path) -> {
                            val id = path.substringAfterLast('/').toInt()
                            detailIds += id
                            active++
                            peak = maxOf(peak, active)
                            try {
                                delay(20)
                                json(show(id))
                            } finally {
                                active--
                            }
                        }
                        path.endsWith("/season/1") -> json("""{"episodes":[]}""")
                        else -> error("Unexpected endpoint: $path")
                    }
                }
            try {
                val result = TmdbDomesticAiringCalendarSource(client).calendar("2026-11-01", "2026-11-12", false)
                assertEquals(8, detailIds.size)
                assertEquals(8, detailIds.toSet().size)
                assertTrue(peak in 1..3, "Peak concurrent details: $peak")
                assertEquals(
                    8,
                    result.episodes
                        .map { it.showTmdbId }
                        .toSet()
                        .size,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun a_stalled_source_exits_within_its_total_budget_and_marks_coverage_incomplete() =
        runTest {
            val client = client { awaitCancellation() }
            try {
                val started = currentTime
                val result =
                    TmdbDomesticAiringCalendarSource(client, totalBudgetMillis = 50, requestTimeoutMillis = 500)
                        .calendar("2026-11-01", "2026-11-12", false)
                assertFalse(result.complete)
                assertTrue(result.episodes.isEmpty())
                assertTrue(currentTime - started <= 50, "Total timeout must include all discovery/detail work")
            } finally {
                client.close()
            }
        }

    @Test
    fun a_failed_forced_refresh_keeps_the_last_successful_dates_and_marks_them_incomplete() =
        runTest {
            var offline = false
            val client =
                client { request ->
                    if (offline) {
                        return@client respond(
                            content = """{"status_message":"Metadata unavailable","status_code":11}""",
                            status = HttpStatusCode.InternalServerError,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    when (request.url.encodedPath) {
                        "/3/discover/tv" -> json("""{"results":[{"id":7,"name":"国产新剧"}]}""")
                        "/3/tv/7" -> json(show(7))
                        "/3/tv/7/season/1" -> json("""{"episodes":[]}""")
                        else -> error("Unexpected endpoint")
                    }
                }
            try {
                val source = TmdbDomesticAiringCalendarSource(client)
                val known = source.calendar("2026-11-01", "2026-11-12", false)
                assertTrue(known.complete)
                assertTrue(known.episodes.isNotEmpty())
                offline = true
                val refreshed = source.calendar("2026-11-01", "2026-11-12", true)
                assertFalse(refreshed.complete)
                assertEquals(known.episodes, refreshed.episodes)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_new_show_without_last_or_next_stubs_uses_only_its_real_current_season_dates() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                client { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/3/discover/tv" -> json("""{"results":[{"id":7,"name":"国产新剧"}]}""")
                        "/3/tv/7" ->
                            json("""{"id":7,"name":"国产新剧","origin_country":["CN"],"number_of_seasons":1}""")
                        "/3/tv/7/season/1" ->
                            json(
                                """{"season_number":1,"episodes":[
                            {"episode_number":1,"air_date":"2026-11-05"},
                            {"episode_number":2,"air_date":null},
                            {"episode_number":3,"air_date":"2026-11-07"}]}""",
                            )
                        else -> error("Unexpected endpoint")
                    }
                }
            try {
                val result = TmdbDomesticAiringCalendarSource(client).calendar("2026-11-01", "2026-11-12", false)
                assertTrue(result.complete)
                assertEquals(listOf(1, 3), result.episodes.map { it.episodeNumber })
                assertEquals(listOf("2026-11-05", "2026-11-07"), result.episodes.map { it.airDate })
                assertEquals(1, paths.count { it.contains("/season/") })
            } finally {
                client.close()
            }
        }

    @Test
    fun closing_a_calendar_cancels_its_requests_without_poisoning_the_next_load() =
        runTest {
            var stall = true
            val started = CompletableDeferred<Unit>()
            val client =
                client { request ->
                    if (stall) {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    when (request.url.encodedPath) {
                        "/3/discover/tv" -> json("""{"results":[{"id":7,"name":"国产新剧"}]}""")
                        "/3/tv/7" -> json(show(7))
                        "/3/tv/7/season/1" -> json("""{"episodes":[]}""")
                        else -> error("Unexpected endpoint")
                    }
                }
            try {
                val source = TmdbDomesticAiringCalendarSource(client)
                val cancelled = async { source.calendar("2026-11-01", "2026-11-12", false) }
                started.await()
                cancelled.cancelAndJoin()
                stall = false
                val next = source.calendar("2026-11-01", "2026-11-12", false)
                assertTrue(next.complete)
                assertEquals(listOf(12, 13), next.episodes.map { it.episodeNumber })
            } finally {
                client.close()
            }
        }

    private fun show(
        id: Int,
        country: String = "CN",
    ) = """{"id":$id,"name":"国产新剧$id","origin_country":["$country"],"number_of_seasons":1,
        "last_episode_to_air":{"season_number":1,"episode_number":12,"air_date":"2026-11-04"},
        "next_episode_to_air":{"season_number":1,"episode_number":13,"air_date":"2026-11-06"}}"""

    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler(handler)
                },
            ),
        ) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
