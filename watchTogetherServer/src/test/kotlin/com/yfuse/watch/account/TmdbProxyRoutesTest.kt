package com.yfuse.watch.account

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbProxyRoutesTest {
    private var clock = 1_000_000L

    // Sessions run on real time; only the proxy and its limiters see the test clock.
    private val backend = AccountBackend.inMemoryForTests()
    private val upstreamCalls = mutableListOf<Pair<String, String>>()
    private var upstreamAnswer: () -> TmdbUpstreamResponse = { json(200, MATRIX) }
    private val upstream =
        TmdbUpstream { pathAndQuery, bearer ->
            synchronized(upstreamCalls) { upstreamCalls += pathAndQuery to bearer }
            upstreamAnswer()
        }

    private fun proxy(
        token: String? = SERVER_TOKEN,
        accountAttempts: Int = TmdbProxy.TMDB_ACCOUNT_RATE_POLICY.tmdbProxyAttemptsPerWindow,
    ) = TmdbProxy(
        token = token,
        upstream = upstream,
        accountLimiter =
            AccountRateLimiter(
                AccountRateLimitPolicy(tmdbProxyAttemptsPerWindow = accountAttempts),
            ) { clock },
        now = { clock },
    )

    @AfterTest
    fun closeBackend() = backend.close()

    @Test
    fun only_a_signed_in_account_reaches_tmdb() =
        proxyTest(proxy()) { _ ->
            val anonymous = client.tmdb("/movie/603", token = null)
            assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
            assertEquals("Bearer", anonymous.headers[HttpHeaders.WWWAuthenticate])
            assertEquals(HttpStatusCode.Unauthorized, client.tmdb("/movie/603", token = "not-a-session").status)
            assertTrue(upstreamCalls.isEmpty())
        }

    @Test
    fun an_allowlisted_read_is_forwarded_with_the_server_bearer_and_answered_verbatim() =
        proxyTest(proxy()) { accessToken ->
            val response = client.tmdb("/movie/603?language=zh-CN&append_to_response=credits", accessToken)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(MATRIX, response.bodyAsText())
            assertEquals("miss", response.headers[TMDB_PROXY_CACHE_HEADER])
            // Canonical order, the server's own bearer, and never the account's.
            assertEquals(listOf("/movie/603?append_to_response=credits&language=zh-CN" to SERVER_TOKEN), upstreamCalls)
            assertFalse(upstreamCalls.any { (_, bearer) -> bearer == accessToken })
        }

    @Test
    fun paths_and_parameters_outside_the_allowlist_are_refused_without_reaching_tmdb() =
        proxyTest(proxy()) { accessToken ->
            listOf(
                "/account/1",
                "/movie/603/videos",
                "/configuration",
                "/movie/603?api_key=other",
                "/movie/603?language=zh-CN&language=en-US",
                "/movie/603?append_to_response=videos",
                "/movie/..%2Fconfiguration",
                "/movie/0603",
                "/tv/1399/season/1?append_to_response=credits",
                "/discover/movie?include_adult=true",
                "/discover/movie?with_origin_country=CN%26api_key%3Dx",
                "/search/person",
                "/search/person?query=%20%20",
                "/search/person?query=a%0Ab",
                "/find/tt0133093",
            ).forEach { path ->
                val response = client.tmdb(path, accessToken)
                assertEquals(HttpStatusCode.Forbidden, response.status, path)
                assertEquals("tmdb_request_not_allowed", response.errorCode(), path)
                assertNull(response.headers[TMDB_PROXY_CACHE_HEADER], path)
            }
            assertTrue(upstreamCalls.isEmpty())
        }

    @Test
    fun answers_are_cached_per_request_until_their_kind_expires() =
        proxyTest(proxy()) { accessToken ->
            assertEquals(
                "miss",
                client.tmdb("/movie/popular?language=zh-CN", accessToken).headers[TMDB_PROXY_CACHE_HEADER],
            )
            val repeated = client.tmdb("/movie/popular?language=zh-CN", accessToken)
            assertEquals("hit", repeated.headers[TMDB_PROXY_CACHE_HEADER])
            assertEquals(MATRIX, repeated.bodyAsText())
            assertEquals(1, upstreamCalls.size)

            // The key is canonical: the same parameters in another order are the same read.
            client.tmdb("/discover/tv?sort_by=popularity.desc&language=zh-CN", accessToken)
            assertEquals(
                "hit",
                client
                    .tmdb(
                        "/discover/tv?language=zh-CN&sort_by=popularity.desc",
                        accessToken,
                    ).headers[TMDB_PROXY_CACHE_HEADER],
            )
            client.tmdb("/tv/1399?language=zh-CN", accessToken)
            assertEquals(3, upstreamCalls.size)

            // Charts expire after minutes; a title record is still good.
            clock += 11 * 60_000L
            assertEquals(
                "miss",
                client.tmdb("/movie/popular?language=zh-CN", accessToken).headers[TMDB_PROXY_CACHE_HEADER],
            )
            assertEquals("hit", client.tmdb("/tv/1399?language=zh-CN", accessToken).headers[TMDB_PROXY_CACHE_HEADER])
            assertEquals(4, upstreamCalls.size)
            clock += 2 * 60 * 60_000L
            assertEquals("miss", client.tmdb("/tv/1399?language=zh-CN", accessToken).headers[TMDB_PROXY_CACHE_HEADER])
            assertEquals(5, upstreamCalls.size)
        }

    @Test
    fun tmdb_answers_other_than_success_are_relayed_but_never_cached() =
        proxyTest(proxy()) { accessToken ->
            upstreamAnswer = { json(404, NOT_FOUND) }
            repeat(2) {
                val missing = client.tmdb("/movie/999999", accessToken)
                assertEquals(HttpStatusCode.NotFound, missing.status)
                assertEquals(NOT_FOUND, missing.bodyAsText())
                assertEquals("miss", missing.headers[TMDB_PROXY_CACHE_HEADER])
            }
            // A 200 that is not JSON (a captive or error page) is neither passed on nor kept.
            upstreamAnswer = { TmdbUpstreamResponse(200, "<html>blocked</html>", "text/html") }
            repeat(2) {
                val page = client.tmdb("/movie/603", accessToken)
                assertEquals(HttpStatusCode.BadGateway, page.status)
                assertEquals("tmdb_unavailable", page.errorCode())
            }
            assertEquals(4, upstreamCalls.size)
        }

    @Test
    fun each_account_has_its_own_budget() =
        proxyTest(proxy(accountAttempts = 2)) { alice ->
            val bob = signIn("Bob")
            repeat(2) { assertEquals(HttpStatusCode.OK, client.tmdb("/tv/1399", alice).status) }
            val limited = client.tmdb("/tv/1399", alice)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("tmdb_rate_limited", limited.errorCode())
            assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
            // Same address, another account: its own budget.
            assertEquals(HttpStatusCode.OK, client.tmdb("/tv/1399", bob).status)
            clock += 60_000L
            assertEquals(HttpStatusCode.OK, client.tmdb("/tv/1399", alice).status)
            assertEquals(1, upstreamCalls.size)
        }

    @Test
    fun a_tmdb_rate_limit_is_passed_on_and_pauses_misses_but_not_cached_answers() =
        proxyTest(proxy()) { accessToken ->
            assertEquals(HttpStatusCode.OK, client.tmdb("/movie/603", accessToken).status)
            upstreamAnswer =
                { TmdbUpstreamResponse(429, """{"status_code":25}""", "application/json", retryAfterSeconds = 7) }
            val limited = client.tmdb("/movie/604", accessToken)
            assertEquals(HttpStatusCode.TooManyRequests, limited.status)
            assertEquals("tmdb_rate_limited", limited.errorCode())
            assertEquals("7", limited.headers[HttpHeaders.RetryAfter])
            assertEquals(2, upstreamCalls.size)

            clock += 3_000L
            val paused = client.tmdb("/movie/605", accessToken)
            assertEquals(HttpStatusCode.TooManyRequests, paused.status)
            assertEquals("4", paused.headers[HttpHeaders.RetryAfter])
            assertEquals(2, upstreamCalls.size)
            assertEquals("hit", client.tmdb("/movie/603", accessToken).headers[TMDB_PROXY_CACHE_HEADER])

            clock += 4_000L
            upstreamAnswer = { json(200, MATRIX) }
            assertEquals(HttpStatusCode.OK, client.tmdb("/movie/605", accessToken).status)
            assertEquals(3, upstreamCalls.size)
        }

    @Test
    fun without_a_server_token_signed_in_requests_get_503() =
        proxyTest(proxy(token = "  ")) { accessToken ->
            assertEquals(HttpStatusCode.Unauthorized, client.tmdb("/movie/603", token = null).status)
            val response = client.tmdb("/movie/603", accessToken)
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("tmdb_unconfigured", response.errorCode())
            assertTrue(upstreamCalls.isEmpty())
        }

    @Test
    fun the_server_token_never_reaches_a_response_or_the_log() {
        val log = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(log, true, Charsets.UTF_8))
        try {
            proxyTest(proxy()) { accessToken ->
                val answers =
                    listOf(
                        // TMDB refusing the server's token is not the account's session failing.
                        { TmdbUpstreamResponse(401, """{"status_code":7}""", "application/json") },
                        { TmdbUpstreamResponse(503, "unavailable", "text/plain") },
                        { throw IOException("Authorization: Bearer $SERVER_TOKEN was refused") },
                        { json(200, MATRIX) },
                    )
                answers.forEachIndexed { index, answer ->
                    upstreamAnswer = answer
                    val response = client.tmdb("/movie/${700 + index}", accessToken)
                    val expected = if (index < 3) HttpStatusCode.BadGateway else HttpStatusCode.OK
                    assertEquals(expected, response.status)
                    assertNull(response.headers[HttpHeaders.WWWAuthenticate])
                    assertFalse(SERVER_TOKEN in response.bodyAsText())
                    assertFalse(response.headers.entries().any { (_, values) -> values.any { SERVER_TOKEN in it } })
                }
            }
        } finally {
            System.setErr(original)
        }
        val written = log.toString(Charsets.UTF_8)
        assertTrue("tmdb_proxy_upstream_failed" in written)
        assertFalse(SERVER_TOKEN in written)
    }

    private fun proxyTest(
        proxy: TmdbProxy,
        block: suspend ApplicationTestBuilder.(accessToken: String) -> Unit,
    ) = testApplication {
        application {
            routing { tmdbProxyRoutes(backend, AccountRateLimiter { clock }, proxy) }
        }
        block(signIn("Alice"))
    }

    private suspend fun signIn(username: String): String =
        backend.execute { register(RegisterRequest(username, PASSWORD)) }.accessToken

    private suspend fun HttpClient.tmdb(
        pathAndQuery: String,
        token: String?,
    ): HttpResponse =
        get("/api/v1/tmdb$pathAndQuery") {
            header("X-Forwarded-Proto", "https")
            header("X-Forwarded-For", CLIENT_IP)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun HttpResponse.errorCode(): String =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private companion object {
        const val SERVER_TOKEN = "server-tmdb-read-token-0123456789"
        const val PASSWORD = "Correct-Horse-42"
        const val CLIENT_IP = "198.51.100.61"
        const val MATRIX = """{"id":603,"title":"黑客帝国"}"""
        const val NOT_FOUND = """{"success":false,"status_code":34}"""

        fun json(
            status: Int,
            body: String,
        ) = TmdbUpstreamResponse(status, body, "application/json;charset=utf-8")
    }
}
