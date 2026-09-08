package com.yfuse.core.data

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TmdbHomeRefreshTest {
    @Test
    fun error_responses_preserve_the_http_category_without_expect_success() =
        runTest {
            val statuses =
                mapOf(
                    HttpStatusCode.Unauthorized to TmdbRecommendationFailure.AUTHORIZATION,
                    HttpStatusCode.Forbidden to TmdbRecommendationFailure.ACCESS_DENIED,
                    HttpStatusCode.TooManyRequests to TmdbRecommendationFailure.RATE_LIMITED,
                    HttpStatusCode.ServiceUnavailable to TmdbRecommendationFailure.SERVICE,
                )
            for ((status, expected) in statuses) {
                val client = client { respondJson("""{"success":false,"status_message":"remote detail"}""", status) }
                try {
                    val result = TmdbRepository(client).refreshHome()
                    val error = result.exceptionOrNull() as TmdbRecommendationException
                    assertEquals(expected, error.failure)
                    assertEquals(expected.name, error.message)
                    assertNull(error.cause)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun response_exception_clients_preserve_authorization_failure() =
        runTest {
            val client = client(expectSuccess = true) { respondJson("{}", HttpStatusCode.Unauthorized) }
            try {
                val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                assertEquals(TmdbRecommendationFailure.AUTHORIZATION, error.failure)
            } finally {
                client.close()
            }
        }

    @Test
    fun successful_empty_catalogues_are_empty_instead_of_network_errors() =
        runTest {
            val client = client { json("""{"results":[]}""") }
            try {
                val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                assertEquals(TmdbRecommendationFailure.EMPTY, error.failure)
            } finally {
                client.close()
            }
        }

    @Test
    fun successful_catalogues_with_only_filtered_titles_are_empty() =
        runTest {
            val client =
                client {
                    json("""{"results":[{"id":1,"title":"Documentary","poster_path":"/p.jpg","genre_ids":[99]}]}""")
                }
            try {
                val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                assertEquals(TmdbRecommendationFailure.EMPTY, error.failure)
            } finally {
                client.close()
            }
        }

    @Test
    fun invalid_json_and_missing_catalogue_payloads_are_invalid_responses() =
        runTest {
            for (body in listOf("not-json", "{}", """{"results":null}""")) {
                val client = client { respondJson(body) }
                try {
                    val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                    assertEquals(TmdbRecommendationFailure.INVALID_RESPONSE, error.failure)
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun successful_non_json_gateway_page_is_an_invalid_response() =
        runTest {
            val client =
                client {
                    respond(
                        "<html>Gateway page</html>",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )
                }
            try {
                val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                assertEquals(TmdbRecommendationFailure.INVALID_RESPONSE, error.failure)
            } finally {
                client.close()
            }
        }

    @Test
    fun transport_failure_is_network_without_retaining_the_transport_exception() =
        runTest {
            val client = client { throw IOException("transport details") }
            try {
                val error = TmdbRepository(client).refreshHome().exceptionOrNull() as TmdbRecommendationException
                assertEquals(TmdbRecommendationFailure.NETWORK, error.failure)
                assertNull(error.cause)
            } finally {
                client.close()
            }
        }

    @Test
    fun only_failed_feed_groups_are_incomplete_when_other_feeds_succeed_empty() =
        runTest {
            val client =
                client { request ->
                    when {
                        request.url.encodedPath.endsWith("/movie/popular") -> json(POPULAR_MOVIE)
                        request.url.encodedPath.endsWith("/tv/popular") ->
                            respondJson("{}", HttpStatusCode.TooManyRequests)
                        else -> json("""{"results":[]}""")
                    }
                }
            try {
                val refresh = TmdbRepository(client).refreshHome().getOrThrow()
                assertEquals(setOf("热门"), refresh.incompleteRows)
                assertEquals(TmdbRecommendationFailure.RATE_LIMITED, refresh.failure)
                assertEquals(
                    42,
                    refresh.content.rows
                        .single()
                        .items
                        .single()
                        .id,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun successful_empty_feeds_preserve_partial_completeness_when_another_feed_fails() =
        runTest {
            val client =
                client { request ->
                    if (request.url.encodedPath.endsWith("/tv/popular")) {
                        respondJson("{}", HttpStatusCode.ServiceUnavailable)
                    } else {
                        json("""{"results":[]}""")
                    }
                }
            try {
                val refresh = TmdbRepository(client).refreshHome().getOrThrow()
                assertTrue(refresh.content.isEmpty)
                assertEquals(setOf("热门"), refresh.incompleteRows)
                assertEquals(TmdbRecommendationFailure.SERVICE, refresh.failure)
            } finally {
                client.close()
            }
        }

    @Test
    fun complete_refresh_may_have_successfully_empty_shelves() =
        runTest {
            val client =
                client { request ->
                    json(
                        if (request.url.encodedPath.endsWith("/movie/popular")) {
                            POPULAR_MOVIE
                        } else {
                            """{"results":[]}"""
                        },
                    )
                }
            try {
                val refresh = TmdbRepository(client).refreshHome().getOrThrow()
                assertTrue(refresh.incompleteRows.isEmpty())
                assertNull(refresh.failure)
                assertEquals(
                    "热门",
                    refresh.content.rows
                        .single()
                        .title,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun parent_cancellation_cancels_active_feeds_and_is_not_a_refresh_failure() =
        runTest {
            var started = 0
            var finished = 0
            val client =
                client {
                    started++
                    try {
                        awaitCancellation()
                    } finally {
                        finished++
                    }
                }
            try {
                val refresh = async { TmdbRepository(client).refreshHome() }
                runCurrent()
                assertTrue(started > 0)
                refresh.cancelAndJoin()
                assertFailsWith<CancellationException> { refresh.await() }
                assertEquals(started, finished)
            } finally {
                client.close()
            }
        }

    private fun TestScope.client(
        expectSuccess: Boolean = false,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = HttpClient(
        MockEngine(
            MockEngineConfig().apply {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            },
        ),
    ) {
        this.expectSuccess = expectSuccess
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private companion object {
        const val POPULAR_MOVIE = """{"results":[{"id":42,"title":"Movie","poster_path":"/p.jpg","genre_ids":[18]}]}"""
    }
}
