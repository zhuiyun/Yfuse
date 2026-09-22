package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbyLookupServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun a_provider_media_key_is_resolved_through_one_provider_id_query() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json("""{"Items":[{"Id":"m1","Name":"The Matrix","Type":"Movie","ProductionYear":1999}]}""")
                }
            try {
                val item = assertNotNull(EmbyLookupService(client).findByMediaKey(server, "TMDB:603").getOrThrow())

                val request = requireNotNull(seen)
                assertEquals("/Users/u1/Items", request.url.encodedPath)
                assertEquals("tmdb.603", request.url.parameters["AnyProviderIdEquals"])
                assertEquals("Movie,Series,Episode", request.url.parameters["IncludeItemTypes"])
                assertEquals("1", request.url.parameters["Limit"])
                assertEquals("token", request.headers["X-Emby-Token"])
                assertEquals("m1", item.id)
                assertEquals("The Matrix", item.title)
                assertEquals(1999, item.year)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_emby_media_key_is_read_as_a_plain_item_id_on_this_server() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json("""{"Id":"abc","Name":"Local Only","Type":"Movie"}""")
                }
            try {
                val item = assertNotNull(EmbyLookupService(client).findByMediaKey(server, "emby:abc").getOrThrow())

                assertEquals("/Users/u1/Items/abc", requireNotNull(seen).url.encodedPath)
                assertEquals("abc", item.id)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_malformed_media_key_misses_without_touching_the_server() =
        runTest {
            var requests = 0
            val client =
                client {
                    requests++
                    json("{}")
                }
            try {
                val service = EmbyLookupService(client)

                assertNull(service.findByMediaKey(server, "nonsense").getOrThrow())
                assertNull(service.findByMediaKey(server, ":603").getOrThrow())
                assertNull(service.findByMediaKey(server, "tmdb:").getOrThrow())
                assertEquals(0, requests)
            } finally {
                client.close()
            }
        }

    @Test
    fun series_index_keys_every_provider_id_in_lower_case_and_skips_blank_values() =
        runTest {
            val client =
                client { request ->
                    assertEquals("Series", request.url.parameters["IncludeItemTypes"])
                    assertEquals("true", request.url.parameters["Recursive"])
                    json(
                        """
                        {"Items":[
                            {"Id":"s1","Name":"Show","ProviderIds":{"Tmdb":"1399","Imdb":"tt0944947","Tvdb":""}},
                            {"Id":"s2","Name":"Other","ProviderIds":{"TMDB":"2"}}
                        ]}
                        """.trimIndent(),
                    )
                }
            try {
                val index = EmbyLookupService(client).seriesProviderIndex(server).getOrThrow()

                assertEquals(
                    mapOf("tmdb:1399" to "s1", "imdb:tt0944947" to "s1", "tmdb:2" to "s2"),
                    index,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun movie_index_carries_the_watched_flag_and_a_title_year_fallback_key() =
        runTest {
            val client =
                client { request ->
                    assertEquals("Movie", request.url.parameters["IncludeItemTypes"])
                    assertEquals("ProductionYear,ProviderIds", request.url.parameters["Fields"])
                    json(
                        """
                        {"Items":[
                            {"Id":"m1","Name":"The Matrix","ProductionYear":1999,"ProviderIds":{"Tmdb":"603"},
                             "UserData":{"Played":true}},
                            {"Id":"m2","Name":"Untitled","ProviderIds":{"Tmdb":"7"}}
                        ]}
                        """.trimIndent(),
                    )
                }
            try {
                val index = EmbyLookupService(client).movieProviderIndex(server).getOrThrow()

                assertEquals(ProviderHit("m1", played = true), index["tmdb:603"])
                assertEquals(ProviderHit("m2", played = false), index["tmdb:7"])
                assertTrue(index.keys.any { it.startsWith("title:") && it.endsWith(":1999") })
                assertTrue(index.keys.none { it.startsWith("title:") && it.endsWith(":null") })
            } finally {
                client.close()
            }
        }

    @Test
    fun identity_catalog_drops_unnamed_rows_and_keeps_favourite_state() =
        runTest {
            val client =
                client {
                    json(
                        """
                        {"Items":[
                            {"Id":"s1","Name":"Show","ProductionYear":2011,"DateCreated":"2026-01-02T00:00:00Z",
                             "ProviderIds":{"Tmdb":"1399"},"UserData":{"IsFavorite":true}},
                            {"Id":"s2","Name":"  "},
                            {"Id":"s3"}
                        ]}
                        """.trimIndent(),
                    )
                }
            try {
                val catalog = EmbyLookupService(client).seriesIdentityCatalog(server).getOrThrow()

                assertEquals(
                    listOf(
                        LibrarySeriesIdentity(
                            itemId = "s1",
                            title = "Show",
                            year = 2011,
                            providerIds = mapOf("Tmdb" to "1399"),
                            dateCreated = "2026-01-02T00:00:00Z",
                            isFavorite = true,
                        ),
                    ),
                    catalog,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun a_server_failure_is_reported_as_the_server_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.InternalServerError) }
            try {
                val result = EmbyLookupService(client).seriesIdentityCatalog(server)

                assertTrue(result.isFailure)
                assertEquals(EmbyError.Server(500), assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
            } finally {
                client.close()
            }
        }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        createEmbyClient(
            appVersion = "test",
            engine =
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = Dispatchers.Unconfined
                        addHandler(handler)
                    },
                ),
            timeouts = null,
        )
}
