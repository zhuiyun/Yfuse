package com.yfuse.core.trakt

import com.yfuse.core.personal.PersonalCollection
import com.yfuse.core.personal.PersonalMediaRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraktLibraryApiTest {
    @Test fun favoritesReadsAllPagesUsingItsOwnEndpoint() =
        runTest {
            val client =
                HttpClient(
                    MockEngine { request ->
                        assertEquals("/sync/favorites", request.url.encodedPath)
                        assertEquals("2", request.url.parameters["page"])
                        respond(
                            """[{"type":"movie","movie":{"title":"Film","ids":{"tmdb":1}}}]""",
                            headers = headersOf("X-Pagination-Page-Count", "3"),
                        )
                    },
                )
            try {
                assertTrue(HttpTraktApi(client).favorites("id", "token", 2).hasNext)
            } finally {
                client.close()
            }
        }

    @Test fun favoriteRemovalUsesFavoritesRatherThanCollection() =
        runTest {
            val client =
                client { path, body ->
                    assertEquals("/sync/favorites/remove", path)
                    assertEquals(
                        "1",
                        body
                            .getValue(
                                "movies",
                            ).jsonArray
                            .single()
                            .jsonObject
                            .getValue("ids")
                            .jsonObject
                            .getValue("tmdb")
                            .jsonPrimitive.content,
                    )
                }
            try {
                HttpTraktApi(
                    client,
                ).writeLibrary(
                    "id",
                    "token",
                    PersonalCollection.Favorite,
                    TraktLibraryMedia("movie", TraktIds(tmdb = 1)),
                    true,
                )
            } finally {
                client.close()
            }
        }

    @Test fun coordinateEpisodeHistoryIsNestedUnderItsShowAndDoesNotMarkWholeShowWatched() =
        runTest {
            val client =
                client { path, body ->
                    assertEquals("/sync/history", path)
                    val show =
                        body
                            .getValue("shows")
                            .jsonArray
                            .single()
                            .jsonObject
                    assertFalse("watched_at" in show)
                    assertEquals(
                        "10",
                        show
                            .getValue("ids")
                            .jsonObject
                            .getValue("tmdb")
                            .jsonPrimitive.content,
                    )
                    val season =
                        show
                            .getValue("seasons")
                            .jsonArray
                            .single()
                            .jsonObject
                    assertEquals("2", season.getValue("number").jsonPrimitive.content)
                    val episode =
                        season
                            .getValue("episodes")
                            .jsonArray
                            .single()
                            .jsonObject
                    assertEquals("3", episode.getValue("number").jsonPrimitive.content)
                    assertTrue(
                        episode
                            .getValue("watched_at")
                            .jsonPrimitive.content
                            .endsWith("Z"),
                    )
                }
            try {
                val media = PersonalMediaRef("tmdb:10/s2e3", "Episode", "Episode").traktMedia()!!
                HttpTraktApi(
                    client,
                ).writeLibrary("id", "token", PersonalCollection.History, media, false, 1_700_000_000_000)
            } finally {
                client.close()
            }
        }

    @Test fun unconfirmedOrNotFoundResponsesNeverCountAsSuccess() =
        runTest {
            for (body in listOf(
                "{}",
                """{"added":{"movies":0},"not_found":{"movies":[{"ids":{"tmdb":1}}]}}""",
                """{"added":{"movies":0},"not_found":{"movies":[]}}""",
            )) {
                val client = HttpClient(MockEngine { respond(body) }) { install(ContentNegotiation) { json() } }
                try {
                    assertFailsWith<IllegalStateException> {
                        HttpTraktApi(
                            client,
                        ).writeLibrary(
                            "id",
                            "token",
                            PersonalCollection.WatchLater,
                            TraktLibraryMedia("movie", TraktIds(tmdb = 1)),
                            false,
                        )
                    }
                } finally {
                    client.close()
                }
            }
        }

    @Test fun accountLimitAndRateLimitAreReportedWithRetryDelay() =
        runTest {
            for (code in listOf(420, 429)) {
                val client =
                    HttpClient(
                        MockEngine { respond("{}", HttpStatusCode.fromValue(code), headersOf("Retry-After", "60")) },
                    ) {
                        install(ContentNegotiation) { json() }
                    }
                try {
                    val failure =
                        assertFailsWith<TraktApiException> {
                            HttpTraktApi(
                                client,
                            ).writeLibrary(
                                "id",
                                "token",
                                PersonalCollection.Favorite,
                                TraktLibraryMedia("movie", TraktIds(tmdb = 1)),
                                false,
                            )
                        }
                    assertEquals(code, failure.status)
                    assertEquals(60, failure.retryAfterSeconds)
                } finally {
                    client.close()
                }
            }
        }

    private fun client(check: (String, kotlinx.serialization.json.JsonObject) -> Unit) =
        HttpClient(
            MockEngine { request ->
                assertEquals("Bearer token", request.headers["Authorization"])
                check(
                    request.url.encodedPath,
                    Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject,
                )
                respond("""{"added":{"episodes":1},"deleted":{"movies":1},"not_found":{"movies":[],"episodes":[]}}""")
            },
        ) { install(ContentNegotiation) { json() } }
}
