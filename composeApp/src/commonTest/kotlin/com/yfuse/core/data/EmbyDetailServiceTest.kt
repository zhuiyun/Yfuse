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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbyDetailServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun item_detail_requests_the_media_information_fields_for_one_item() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json("""{"Id":"m1","Name":"The Matrix","Type":"Movie","ProductionYear":1999}""")
                }
            try {
                val detail = EmbyDetailService(client).itemDetail(server, "m1").getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("/Users/u1/Items/m1", request.url.encodedPath)
                assertEquals("token", request.headers["X-Emby-Token"])
                val fields = requireNotNull(request.url.parameters["Fields"]).split(',')
                assertTrue("MediaSources" in fields)
                assertTrue("Path" in fields)
                assertEquals("m1", detail.id)
                assertEquals("Movie", detail.type)
                assertTrue(detail.people.isEmpty())
            } finally {
                client.close()
            }
        }

    @Test
    fun an_episode_without_its_own_cast_borrows_the_series_cast() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                client { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/Users/u1/Items/e1" ->
                            json("""{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"s1","SeriesName":"Show"}""")
                        "/Users/u1/Items/s1" -> {
                            assertEquals("People", request.url.parameters["Fields"])
                            json(
                                """{"Id":"s1","Name":"Show","Type":"Series","People":[{"Id":"p1","Name":"Actor","Role":"Neo"}]}""",
                            )
                        }
                        else -> error("unexpected request ${request.url}")
                    }
                }
            try {
                val detail = EmbyDetailService(client).itemDetail(server, "e1").getOrThrow()

                assertEquals(listOf("/Users/u1/Items/e1", "/Users/u1/Items/s1"), paths)
                assertEquals("Episode", detail.type)
                assertEquals(listOf("Actor"), detail.people.map { it.name })
            } finally {
                client.close()
            }
        }

    @Test
    fun seasons_fall_back_to_a_numbered_name_when_the_server_omits_one() =
        runTest {
            val client =
                client { request ->
                    assertEquals("/Shows/s1/Seasons", request.url.encodedPath)
                    assertEquals("u1", request.url.parameters["UserId"])
                    json("""{"Items":[{"Id":"se1","Name":"Season 1","IndexNumber":1},{"Id":"se2","IndexNumber":2}]}""")
                }
            try {
                val seasons = EmbyDetailService(client).seasons(server, "s1").getOrThrow()

                assertEquals(listOf("Season 1", "第 2 季"), seasons.map { it.name })
                assertEquals(listOf(1, 2), seasons.map { it.indexNumber })
            } finally {
                client.close()
            }
        }

    @Test
    fun episodes_are_scoped_to_the_season_and_only_ask_for_media_sources_on_request() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json(
                        """
                        {"Items":[
                            {"Id":"e1","Name":"Pilot","IndexNumber":1,"ParentIndexNumber":1,"SeasonId":"se1"},
                            {"Id":"e2","Name":"Second","IndexNumber":2,"ParentIndexNumber":1,"SeasonId":"se1"}
                        ]}
                        """.trimIndent(),
                    )
                }
            try {
                val episodes = EmbyDetailService(client).episodes(server, "s1", seasonId = "se1").getOrThrow()

                val request = requireNotNull(seen)
                assertEquals("/Shows/s1/Episodes", request.url.encodedPath)
                assertEquals("se1", request.url.parameters["SeasonId"])
                assertNull(request.url.parameters["Season"])
                assertTrue("MediaSources" !in requireNotNull(request.url.parameters["Fields"]))
                assertEquals(listOf("e1", "e2"), episodes.map { it.id })
                assertEquals(listOf(1, 2), episodes.map { it.indexNumber })
                assertEquals(listOf(1, 1), episodes.map { it.seasonNumber })
            } finally {
                client.close()
            }
        }

    @Test
    fun a_missing_item_maps_to_the_not_found_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.NotFound) }
            try {
                val result = EmbyDetailService(client).itemDetail(server, "gone")

                assertTrue(result.isFailure)
                assertEquals(EmbyError.NotFound, assertIs<EmbyErrorException>(result.exceptionOrNull()).error)
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
