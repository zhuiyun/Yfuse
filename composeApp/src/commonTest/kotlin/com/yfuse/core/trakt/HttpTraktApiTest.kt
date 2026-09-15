package com.yfuse.core.trakt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpTraktApiTest {
    @Test
    fun serverPaginationClampingDoesNotTruncateImportAndUsesOnlyTraktToken() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("api.trakt.tv", request.url.host)
                        assertEquals("/sync/history", request.url.encodedPath)
                        assertEquals("Bearer trakt-token", request.headers[HttpHeaders.Authorization])
                        assertEquals("public-id", request.headers["trakt-api-key"])
                        assertEquals("2", request.headers["trakt-api-version"])
                        assertEquals("100", request.url.parameters["limit"])
                        respond(
                            """[{"type":"movie","movie":{"title":"Movie","ids":{"tmdb":603}}}]""",
                            headers = headersOf("X-Pagination-Page-Count", "3"),
                        )
                    },
                )
            try {
                val page = HttpTraktApi(client).history("public-id", "trakt-token", 1, "2026-09-15T00:00:00Z")
                assertEquals(1, page.items.size)
                assertTrue(page.hasNext)
            } finally {
                client.close()
            }
        }

    @Test
    fun missingPaginationAndOversizeBodyFailExplicitly() =
        runTest {
            for (body in listOf("""[{"type":"movie"}]""", " ".repeat(2 * 1024 * 1024 + 1))) {
                val client = HttpClient(MockEngine { respond(body) })
                try {
                    assertFailsWith<IllegalStateException> { HttpTraktApi(client).watchlist("id", "token", 1) }
                } finally {
                    client.close()
                }
            }
        }

    @Test
    fun stopDuplicateIsSuccessButRateLimitRetainsRetryAfter() =
        runTest {
            var status = HttpStatusCode.Conflict
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("/scrobble/stop", request.url.encodedPath)
                        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        assertEquals("95.0", body.getValue("progress").jsonPrimitive.content)
                        assertTrue("movie" in body)
                        respond("{}", status, headersOf("Retry-After", "42"))
                    },
                ) { install(ContentNegotiation) { json() } }
            try {
                val api = HttpTraktApi(client)
                val media = TraktPlaybackMedia("movie", TraktIds(tmdb = 603))
                api.scrobble("id", "token", media, TraktPlaybackAction.Stop, 95f)
                status = HttpStatusCode.TooManyRequests
                assertEquals(
                    42,
                    assertFailsWith<TraktApiException> {
                        api.scrobble("id", "token", media, TraktPlaybackAction.Stop, 95f)
                    }.retryAfterSeconds,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun episodeImportUsesSeriesCoordinatesAndSeriesCannotBeScrobbled() {
        val item =
            TraktListItem(
                "episode",
                show = TraktTitle("Show", ids = TraktIds(tmdb = 100)),
                episode = TraktEpisode("Episode", 2, 3, TraktIds(tmdb = 999)),
            )
        assertEquals("tmdb:100/s2e3", item.toPersonalMedia()?.mediaKey)
        assertEquals("Episode", item.toPersonalMedia()?.mediaType)
        assertNull(traktPlaybackMedia("Series", mapOf("Tmdb" to "100")))
        assertEquals(999, traktPlaybackMedia("Episode", mapOf("Tmdb" to "999"))?.ids?.tmdb)
    }
}
