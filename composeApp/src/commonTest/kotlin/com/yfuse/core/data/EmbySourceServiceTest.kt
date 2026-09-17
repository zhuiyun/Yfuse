package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbySourceServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun a_provider_match_reports_the_best_source_of_the_current_server() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                client { request ->
                    requests += request
                    json(
                        """
                        {"Items":[{
                            "Id":"m1","Name":"The Matrix","Type":"Movie","ProductionYear":1999,
                            "MediaSources":[{
                                "Id":"src","Size":1000000000,"Bitrate":5000000,
                                "MediaStreams":[
                                    {"Type":"Video","Codec":"h264","Width":1920,"Height":1080},
                                    {"Type":"Audio","Codec":"aac"},
                                    {"Type":"Subtitle","Codec":"srt"}
                                ]
                            }]
                        }]}
                        """.trimIndent(),
                    )
                }
            try {
                val sources =
                    service(client).compareSources(
                        servers = listOf(server),
                        currentServerId = "one",
                        title = "The Matrix",
                        tmdbId = 603,
                        mediaType = "movie",
                        year = 1999,
                    )

                assertEquals(1, requests.size)
                assertEquals("tmdb.603", requests.single().url.parameters["AnyProviderIdEquals"])
                assertEquals("Movie", requests.single().url.parameters["IncludeItemTypes"])
                val source = sources.single()
                assertEquals("one", source.serverId)
                assertTrue(source.isCurrent)
                assertTrue(source.reachable)
                assertEquals("m1", source.itemId)
                val info = assertNotNull(source.source)
                assertEquals(1080, info.videoHeight)
                assertEquals(1_000_000_000L, info.sizeBytes)
                assertEquals(1, info.audioTrackCount)
                assertEquals(1, info.subtitleTrackCount)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_series_without_the_requested_episode_is_listed_without_a_source() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                client { request ->
                    paths += request.url.encodedPath
                    when {
                        request.url.encodedPath.endsWith("/Episodes") -> {
                            assertEquals("1", request.url.parameters["Season"])
                            json("""{"Items":[]}""")
                        }
                        else -> json("""{"Items":[{"Id":"s1","Name":"Show","Type":"Series"}]}""")
                    }
                }
            try {
                val source =
                    service(client)
                        .compareSources(
                            servers = listOf(server),
                            currentServerId = null,
                            title = "Show",
                            tmdbId = 1399,
                            mediaType = "tv",
                            seasonNumber = 1,
                            episodeNumber = 1,
                        ).single()

                assertEquals(listOf("/Users/u1/Items", "/Shows/s1/Episodes"), paths)
                assertTrue(source.reachable)
                assertFalse(source.isCurrent)
                assertEquals("s1", source.itemId)
                assertNull(source.source)
            } finally {
                client.close()
            }
        }

    @Test
    fun an_unreachable_server_is_retried_and_then_degrades_instead_of_failing_the_comparison() =
        runTest {
            var requests = 0
            val client =
                client {
                    requests++
                    throw IOException("offline")
                }
            try {
                val source =
                    service(client)
                        .compareSources(
                            servers = listOf(server),
                            currentServerId = "one",
                            title = "The Matrix",
                            tmdbId = 603,
                        ).single()

                assertEquals(3, requests)
                assertFalse(source.reachable)
                assertNull(source.itemId)
                assertNull(source.source)
                assertEquals("Media", source.serverName)
            } finally {
                client.close()
            }
        }

    private fun service(client: HttpClient) = EmbySourceService(client, EmbyDetailService(client))

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
