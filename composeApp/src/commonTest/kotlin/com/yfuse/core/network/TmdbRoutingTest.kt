package com.yfuse.core.network

import com.yfuse.backend.BackendAccess
import com.yfuse.core.data.TmdbRecommendationException
import com.yfuse.core.data.TmdbRecommendationFailure
import com.yfuse.core.data.TmdbRepository
import com.yfuse.core.model.TmdbItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TmdbRoutingTest {
    private var clock = 1_000_000L
    private val session = MutableStateFlow(true)
    private var accountToken: String? = "account-1"
    private var tokenRequests = 0
    private var refreshes = 0
    private val sent = MutableStateFlow<List<Pair<String, String?>>>(emptyList())

    private fun account() =
        TmdbAccountAccess(
            proxyBase = PROXY,
            sessionAvailable = session,
            accessToken = {
                tokenRequests++
                accountToken
            },
            refreshAccessToken = {
                refreshes++
                accountToken?.let { "account-${refreshes + 1}" }.also { accountToken = it }
            },
        )

    private fun client(
        builtInToken: String = BUILT_IN,
        account: TmdbAccountAccess? = account(),
        backendAccess: BackendAccess = BackendAccess.Default,
        answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") },
    ): HttpClient =
        createTmdbClient(
            engine =
                MockEngine { request ->
                    sent.update { it + (request.url.toString() to request.headers[HttpHeaders.Authorization]) }
                    answer(this, request)
                },
            account = account,
            builtInToken = { builtInToken },
            nowEpochMs = { clock },
            backendAccess = backendAccess,
        )

    @Test
    fun the_proxy_is_preferred_whenever_a_session_exists() {
        // proxy configured, session available, built-in token, proxy cooling down
        assertEquals(TmdbRoute.Proxy, chooseTmdbRoute(true, true, true, false))
        assertEquals(TmdbRoute.Proxy, chooseTmdbRoute(true, true, false, false))
        assertEquals(TmdbRoute.Direct, chooseTmdbRoute(true, true, true, true))
        // Cooling down means nothing without another way in.
        assertEquals(TmdbRoute.Proxy, chooseTmdbRoute(true, true, false, true))
        assertEquals(TmdbRoute.Direct, chooseTmdbRoute(true, false, true, false))
        // No session flag yet and no token: the account may still be restoring one.
        assertEquals(TmdbRoute.Proxy, chooseTmdbRoute(true, false, false, false))
        assertEquals(TmdbRoute.Direct, chooseTmdbRoute(false, false, true, false))
        assertEquals(TmdbRoute.Unavailable, chooseTmdbRoute(false, false, false, false))
    }

    @Test
    fun only_answers_the_proxy_could_not_give_are_retried_directly() {
        assertEquals(TmdbProxyVerdict.Keep, tmdbProxyVerdict(404, relayedByProxy = true, retryAfterSeconds = null))
        assertEquals(TmdbProxyVerdict.Keep, tmdbProxyVerdict(422, relayedByProxy = true, retryAfterSeconds = null))
        assertEquals(TmdbProxyVerdict.RetryDirect(120_000L), tmdbProxyVerdict(404, false, null))
        assertEquals(TmdbProxyVerdict.RetryDirect(120_000L), tmdbProxyVerdict(503, false, null))
        assertEquals(TmdbProxyVerdict.RetryDirect(7_000L), tmdbProxyVerdict(429, false, 7))
        assertEquals(TmdbProxyVerdict.RetryDirect(120_000L), tmdbProxyVerdict(429, false, 86_400))
        assertEquals(TmdbProxyVerdict.RetryDirect(30_000L), tmdbProxyVerdict(429, false, null))
        assertEquals(TmdbProxyVerdict.RetryDirect(0L), tmdbProxyVerdict(403, false, null))
        assertEquals(TmdbProxyVerdict.RetryDirect(0L), tmdbProxyVerdict(502, false, null))
        assertEquals(TmdbProxyVerdict.RetryDirect(0L), tmdbProxyVerdict(401, false, null))
    }

    @Test
    fun a_signed_in_request_goes_through_the_proxy_with_only_the_account_bearer() =
        runTest {
            client().get("$TMDB_BASE/tv/1399") { parameter("language", "zh-CN") }.bodyAsText()
            assertEquals(listOf("$PROXY/tv/1399?language=zh-CN" to "Bearer account-1"), sent.value)
        }

    @Test
    fun a_signed_out_request_goes_direct_with_the_built_in_token() =
        runTest {
            session.value = false
            client().get("$TMDB_BASE/movie/603") { parameter("language", "zh-CN") }.bodyAsText()
            assertEquals(listOf("$TMDB_BASE/movie/603?language=zh-CN" to "Bearer $BUILT_IN"), sent.value)
            assertEquals(0, tokenRequests)
        }

    @Test
    fun other_hosts_get_neither_credential() =
        runTest {
            client().get("https://image.tmdb.org/t/p/w500/poster.jpg").bodyAsText()
            assertEquals(listOf("https://image.tmdb.org/t/p/w500/poster.jpg" to null), sent.value)
        }

    @Test
    fun redirects_to_another_origin_do_not_forward_either_credential() =
        runTest {
            for ((signedIn, token) in listOf(true to BUILT_IN, true to "", false to BUILT_IN)) {
                session.value = signedIn
                sent.value = emptyList()
                val destination = "https://other.example/movie/1"
                client(builtInToken = token) { request ->
                    if (request.url.host == "other.example") {
                        json("{}")
                    } else {
                        respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, destination))
                    }
                }.use { it.get("$TMDB_BASE/movie/1").bodyAsText() }
                val expectedOrigin = if (signedIn) PROXY else TMDB_BASE
                val expectedToken = if (signedIn) "account-1" else BUILT_IN
                assertEquals("$expectedOrigin/movie/1" to "Bearer $expectedToken", sent.value.first())
                assertEquals(destination to null, sent.value.last())
                // A proxy redirect may first use the existing direct fallback. Each credential
                // must still stay on its own origin, with or without that fallback available.
                sent.value.forEach { (url, bearer) ->
                    val expected =
                        when {
                            url.startsWith(PROXY) -> "Bearer account-1"
                            url.startsWith(TMDB_BASE) -> "Bearer $token"
                            else -> null
                        }
                    assertEquals(expected, bearer, url)
                }
            }
        }

    @Test
    fun with_neither_route_tmdb_fails_the_way_an_unreachable_tmdb_does() =
        runTest {
            session.value = false
            accountToken = null
            val client = client(builtInToken = "")
            clock += 60_000L
            assertFailsWith<TmdbUnavailableException> { client.get("$TMDB_BASE/movie/603") }
            assertFailsWith<TmdbUnavailableException> {
                client(
                    builtInToken = "",
                    account = null,
                ).get("$TMDB_BASE/tv/1")
            }

            // The repository's existing failure paths take it from there: a network error, no crash.
            val repository = TmdbRepository(client)
            val detail = repository.detail(TmdbItem(603, "黑客帝国", null, null, null, "1999", "movie", null))
            assertEquals(EmbyError.Network, assertIs<EmbyErrorException>(detail.exceptionOrNull()).error)
            val home = repository.refreshHome()
            assertEquals(
                TmdbRecommendationFailure.NETWORK,
                assertIs<TmdbRecommendationException>(home.exceptionOrNull()).failure,
            )
            assertTrue(sent.value.isEmpty())
        }

    @Test
    fun a_build_without_a_token_waits_for_the_session_restored_at_launch() =
        runTest {
            session.value = false
            accountToken = null
            val client = client(builtInToken = "")
            val read = async { client.get("$TMDB_BASE/tv/1399").bodyAsText() }
            runCurrent()
            assertTrue(sent.value.isEmpty())
            accountToken = "account-1"
            session.value = true
            read.await()
            assertEquals(listOf("$PROXY/tv/1399" to "Bearer account-1"), sent.value)
        }

    @Test
    fun a_refused_session_is_renewed_once_for_every_request_refused_with_it() =
        runTest {
            accountToken = "stale"
            val client =
                client { request ->
                    if (request.headers[HttpHeaders.Authorization] == "Bearer stale") {
                        respond("""{"error":{"code":"unauthorized"}}""", HttpStatusCode.Unauthorized)
                    } else {
                        json("{}")
                    }
                }
            coroutineScope {
                repeat(3) { index -> launch { client.get("$TMDB_BASE/tv/${index + 1}").bodyAsText() } }
            }
            assertEquals(1, refreshes)
            assertTrue(sent.value.all { (url, _) -> url.startsWith(PROXY) })
            assertEquals(3, sent.value.count { (_, bearer) -> bearer == "Bearer account-2" })
        }

    @Test
    fun a_session_the_server_keeps_refusing_falls_back_to_the_built_in_token() =
        runTest {
            val client =
                client { request ->
                    if (request.url.toString().startsWith(
                            PROXY,
                        )
                    ) {
                        respond("{}", HttpStatusCode.Unauthorized)
                    } else {
                        json("{}")
                    }
                }
            client.get("$TMDB_BASE/tv/1").bodyAsText()
            assertEquals(
                listOf(
                    "$PROXY/tv/1" to "Bearer account-1",
                    "$PROXY/tv/1" to "Bearer account-2",
                    "$TMDB_BASE/tv/1" to "Bearer $BUILT_IN",
                ),
                sent.value,
            )
        }

    @Test
    fun an_account_server_without_the_route_is_skipped_for_a_while() =
        runTest {
            val client =
                client { request ->
                    if (request.url.toString().startsWith(PROXY)) respond("", HttpStatusCode.NotFound) else json("{}")
                }
            client.get("$TMDB_BASE/tv/1").bodyAsText()
            client.get("$TMDB_BASE/tv/2").bodyAsText()
            clock += 2 * 60_000L + 1L
            client.get("$TMDB_BASE/tv/3").bodyAsText()
            assertEquals(
                listOf(
                    "$PROXY/tv/1" to "Bearer account-1",
                    "$TMDB_BASE/tv/1" to "Bearer $BUILT_IN",
                    "$TMDB_BASE/tv/2" to "Bearer $BUILT_IN",
                    "$PROXY/tv/3" to "Bearer account-1",
                    "$TMDB_BASE/tv/3" to "Bearer $BUILT_IN",
                ),
                sent.value,
            )
        }

    @Test
    fun a_read_the_allowlist_declines_is_retried_directly_without_skipping_the_proxy() =
        runTest {
            val client =
                client { request ->
                    val url = request.url.toString()
                    if (url.startsWith(PROXY) && url.endsWith("/videos")) {
                        respond("""{"error":{"code":"tmdb_request_not_allowed"}}""", HttpStatusCode.Forbidden)
                    } else {
                        json("{}")
                    }
                }
            client.get("$TMDB_BASE/movie/603/videos").bodyAsText()
            client.get("$TMDB_BASE/movie/603").bodyAsText()
            assertEquals(
                listOf(
                    "$PROXY/movie/603/videos" to "Bearer account-1",
                    "$TMDB_BASE/movie/603/videos" to "Bearer $BUILT_IN",
                    "$PROXY/movie/603" to "Bearer account-1",
                ),
                sent.value,
            )
        }

    @Test
    fun a_tmdb_answer_relayed_by_the_proxy_is_final() =
        runTest {
            val client =
                client {
                    respond(
                        """{"success":false,"status_code":34}""",
                        HttpStatusCode.NotFound,
                        headersOf(
                            HttpHeaders.ContentType to listOf("application/json"),
                            CACHE_HEADER to listOf("miss"),
                        ),
                    )
                }
            val missing = assertFailsWith<ClientRequestException> { client.get("$TMDB_BASE/movie/999999") }
            assertEquals(HttpStatusCode.NotFound, missing.response.status)
            assertEquals(listOf("$PROXY/movie/999999" to "Bearer account-1"), sent.value)
        }

    @Test
    fun without_a_built_in_token_the_proxy_answer_stands() =
        runTest {
            val client = client(builtInToken = "") { respond("{}", HttpStatusCode.ServiceUnavailable) }
            val unavailable = assertFailsWith<ServerResponseException> { client.get("$TMDB_BASE/tv/1") }
            assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.response.status)
            assertEquals(listOf("$PROXY/tv/1" to "Bearer account-1"), sent.value)
        }

    @Test
    fun an_unreachable_account_server_is_skipped_for_a_while_and_the_read_goes_direct() =
        runTest {
            val client =
                client { request ->
                    if (request.url.toString().startsWith(PROXY)) throw IOException("connection refused")
                    json("{}")
                }
            client.get("$TMDB_BASE/tv/1").bodyAsText()
            // Within the cooldown the server is not asked again, so a dead one costs one timeout.
            client.get("$TMDB_BASE/tv/2").bodyAsText()
            clock += 2 * 60_000L + 1L
            client.get("$TMDB_BASE/tv/3").bodyAsText()
            assertEquals(
                listOf(
                    "$PROXY/tv/1" to "Bearer account-1",
                    "$TMDB_BASE/tv/1" to "Bearer $BUILT_IN",
                    "$TMDB_BASE/tv/2" to "Bearer $BUILT_IN",
                    "$PROXY/tv/3" to "Bearer account-1",
                    "$TMDB_BASE/tv/3" to "Bearer $BUILT_IN",
                ),
                sent.value,
            )
        }

    @Test
    fun without_a_built_in_token_an_unreachable_account_server_fails_the_read() =
        runTest {
            val client = client(builtInToken = "") { throw IOException("connection refused") }
            assertFailsWith<IOException> { client.get("$TMDB_BASE/tv/1") }
            assertEquals(listOf("$PROXY/tv/1" to "Bearer account-1"), sent.value)
        }

    @Test
    fun disabled_backend_keeps_direct_tmdb_reads_without_reading_account_tokens() =
        runTest {
            client(backendAccess = BackendAccess(enabled = false)).use {
                it.get("$TMDB_BASE/movie/603").bodyAsText()
            }
            assertEquals(listOf("$TMDB_BASE/movie/603" to "Bearer $BUILT_IN"), sent.value)
            assertEquals(0, tokenRequests)
            assertEquals(0, refreshes)
        }

    @Test
    fun disabled_backend_without_a_builtin_token_never_waits_for_or_requests_the_proxy() =
        runTest {
            session.value = false
            client(builtInToken = "", backendAccess = BackendAccess(enabled = false)).use {
                assertFailsWith<TmdbUnavailableException> { it.get("$TMDB_BASE/movie/603") }
            }
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(0, tokenRequests)
            assertEquals(0, refreshes)
            assertTrue(sent.value.isEmpty())
        }

    private companion object {
        const val PROXY = "https://account.example/api/v1/tmdb"
        const val BUILT_IN = "built-in"
        const val CACHE_HEADER = "X-Yfuse-Tmdb-Cache"

        fun MockRequestHandleScope.json(body: String): HttpResponseData =
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}
