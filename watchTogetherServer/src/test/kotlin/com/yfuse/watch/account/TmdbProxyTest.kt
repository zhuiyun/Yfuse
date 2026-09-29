package com.yfuse.watch.account

import com.sun.net.httpserver.HttpServer
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbProxyTest {
    private val account = AuthenticatedAccount("user-1", "session-1", "alice", "Alice", 0, Long.MAX_VALUE)

    @Test
    fun every_read_the_app_makes_is_allowlisted() {
        // Mirrors the app's requests: home shelves, calendar discovery, details and seasons
        // (TmdbRepository), 其他作品 (TmdbPeopleService) and 文件来源 matching (searchTitles and
        // alternativeTitles), with the parameters each sends.
        val reads =
            listOf(
                "movie/popular" to mapOf("language" to "zh-CN"),
                "tv/popular" to mapOf("language" to "zh-CN"),
                "movie/now_playing" to mapOf("language" to "zh-CN"),
                "tv/airing_today" to mapOf("language" to "zh-CN"),
                "discover/movie" to
                    mapOf(
                        "language" to "zh-CN",
                        "with_origin_country" to "CN|HK|TW",
                        "with_original_language" to "zh",
                        "primary_release_date.gte" to "2026-09-29",
                        "primary_release_date.lte" to "2027-12-31",
                        "sort_by" to "popularity.desc",
                        "without_genres" to "99,10763,10764,10767",
                        "include_adult" to "false",
                        "include_video" to "false",
                    ),
                "discover/movie" to
                    mapOf(
                        "language" to "zh-CN",
                        "with_origin_country" to "CN",
                        "primary_release_year" to "2026",
                        "primary_release_date.lte" to "2026-09-29",
                        "region" to "CN",
                        "vote_count.gte" to "10",
                    ),
                "discover/tv" to
                    mapOf(
                        "language" to "zh-CN",
                        "air_date.gte" to "2026-09-29",
                        "air_date.lte" to "2026-10-12",
                        "first_air_date_year" to "2026",
                        "first_air_date.lte" to "2026-09-29",
                        "vote_count.gte" to "3",
                    ),
                "movie/603" to mapOf("language" to "zh-CN", "append_to_response" to "credits"),
                "tv/1399" to mapOf("language" to "zh-CN"),
                "tv/1399/season/8" to mapOf("language" to "zh-CN"),
                "person/6384" to mapOf("language" to "zh-CN", "append_to_response" to "combined_credits,translations"),
                "person/6384/combined_credits" to mapOf("language" to "zh-CN"),
                "search/person" to mapOf("query" to "基努·里维斯", "language" to "zh-CN", "include_adult" to "false"),
                "search/movie" to
                    mapOf(
                        "query" to "The Wandering Earth",
                        "language" to "zh-CN",
                        "include_adult" to "false",
                        "primary_release_year" to "2019",
                    ),
                "search/tv" to
                    mapOf(
                        "query" to "Sousou no Frieren",
                        "language" to "zh-CN",
                        "include_adult" to "false",
                        "first_air_date_year" to "2023",
                    ),
                "movie/535167/alternative_titles" to emptyMap(),
                "tv/209867/alternative_titles" to emptyMap(),
                "find/tt0133093" to mapOf("external_source" to "imdb_id"),
                "tv/1399/external_ids" to emptyMap(),
            )
        reads.forEach { (path, parameters) ->
            assertNotNull(tmdbProxyRequest(path.split('/'), parameters.toParameters()), path)
        }
    }

    @Test
    fun the_forwarded_request_is_canonical_and_encoded() {
        val search =
            tmdbProxyRequest(
                listOf("search", "person"),
                mapOf("query" to "周 星驰&page=2", "language" to "zh-CN").toParameters(),
            )
        assertEquals(
            "/search/person?language=zh-CN&query=%E5%91%A8%20%E6%98%9F%E9%A9%B0%26page%3D2",
            search?.pathAndQuery,
        )
        assertEquals("search/person", search?.label)
        assertEquals(TmdbFreshness.Search.ttlMs, search?.ttlMs)
        val discover =
            tmdbProxyRequest(
                listOf("discover", "tv"),
                mapOf("with_origin_country" to "CN|HK|TW", "language" to "zh-CN").toParameters(),
            )
        assertEquals("/discover/tv?language=zh-CN&with_origin_country=CN%7CHK%7CTW", discover?.pathAndQuery)
        assertEquals(TmdbFreshness.Listing.ttlMs, discover?.ttlMs)
        assertEquals(TmdbFreshness.Detail.ttlMs, tmdbProxyRequest(listOf("tv", "1399"), Parameters.Empty)?.ttlMs)
        assertTrue(TmdbFreshness.Listing.ttlMs < TmdbFreshness.Detail.ttlMs)
    }

    @Test
    fun the_cache_is_bounded_by_entries_and_by_weight_and_skips_oversized_answers() {
        val byCount = TmdbResponseCache(maxEntries = 2)
        byCount.put("/a", "1", expiresAt = 100, now = 0)
        byCount.put("/b", "2", expiresAt = 100, now = 0)
        assertEquals("1", byCount.get("/a", now = 1))
        byCount.put("/c", "3", expiresAt = 100, now = 1)
        // "/b" was the least recently used once "/a" was read.
        assertNull(byCount.get("/b", now = 2))
        assertEquals("1", byCount.get("/a", now = 2))
        assertEquals("3", byCount.get("/c", now = 2))

        val byWeight = TmdbResponseCache(maxWeight = 3_000, maxEntryWeight = 1_500)
        byWeight.put("/x", "x".repeat(400), expiresAt = 100, now = 0)
        byWeight.put("/y", "y".repeat(400), expiresAt = 100, now = 0)
        byWeight.put("/z", "z".repeat(400), expiresAt = 100, now = 0)
        assertTrue(byWeight.weight() <= 3_000)
        assertEquals(2, byWeight.size())
        assertNull(byWeight.get("/x", now = 1))
        byWeight.put("/huge", "h".repeat(800), expiresAt = 100, now = 1)
        assertNull(byWeight.get("/huge", now = 2))
        assertEquals(2, byWeight.size())
    }

    @Test
    fun expired_answers_are_dropped_before_live_ones() {
        val cache = TmdbResponseCache(maxEntries = 2)
        cache.put("/live", "1", expiresAt = 1_000, now = 0)
        cache.put("/short", "2", expiresAt = 10, now = 0)
        cache.put("/new", "3", expiresAt = 1_000, now = 20)
        assertEquals("1", cache.get("/live", now = 21))
        assertEquals("3", cache.get("/new", now = 21))
        assertNull(cache.get("/new", now = 1_000))
        assertEquals(1, cache.size())
    }

    @Test
    fun the_jdk_transport_sends_only_the_server_bearer_and_bounds_the_body() {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/3/") { exchange ->
                    seen += exchange.requestURI.toString() to exchange.requestHeaders.getFirst("Authorization")
                    val body =
                        if (exchange.requestURI.path.endsWith("/huge")) {
                            ByteArray(4 * 1024 * 1024 + 1) { 'x'.code.toByte() }
                        } else {
                            """{"id":603}""".toByteArray()
                        }
                    exchange.responseHeaders.add("Content-Type", "application/json;charset=utf-8")
                    exchange.responseHeaders.add("Retry-After", "9")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
                start()
            }
        try {
            val upstream = JdkTmdbUpstream("http://127.0.0.1:${server.address.port}/3")
            runBlocking {
                val answer = upstream.get("/movie/603?language=zh-CN", "server-token")
                assertEquals(200, answer.status)
                assertEquals("""{"id":603}""", answer.body)
                assertEquals("application/json;charset=utf-8", answer.contentType)
                assertEquals(9L, answer.retryAfterSeconds)
                assertFailsWith<IOException> { upstream.get("/huge", "server-token") }
            }
            assertEquals("/3/movie/603?language=zh-CN" to "Bearer server-token", seen.first())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun a_full_upstream_queue_answers_busy_instead_of_holding_more_bodies(): Unit =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val proxy =
                TmdbProxy(
                    token = "server-token",
                    upstream =
                        TmdbUpstream { _, _ ->
                            entered.complete(Unit)
                            release.await()
                            TmdbUpstreamResponse(200, "{}", "application/json")
                        },
                    maxConcurrentUpstream = 1,
                    upstreamQueueWaitMs = 50,
                )
            val first = async { proxy.fetch(account, listOf("tv", "1"), Parameters.Empty) }
            entered.await()
            val busy = proxy.fetch(account, listOf("tv", "2"), Parameters.Empty)
            assertIs<TmdbProxyResult.Refused>(busy)
            assertEquals(HttpStatusCode.ServiceUnavailable, busy.status)
            assertEquals("tmdb_busy", busy.code)
            assertEquals(2L, busy.retryAfterSeconds)
            release.complete(Unit)
            assertIs<TmdbProxyResult.Relayed>(first.await())
        }

    private fun Map<String, String>.toParameters(): Parameters =
        parametersOf(*entries.map { (name, value) -> name to listOf(value) }.toTypedArray())
}
