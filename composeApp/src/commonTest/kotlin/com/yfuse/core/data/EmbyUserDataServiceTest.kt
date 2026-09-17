package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.createEmbyClient
import com.yfuse.core.sync.SyncedUserItem
import com.yfuse.feature.json
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbyUserDataServiceTest {
    private val server = SavedServer("one", "http://host:8096", "Media", "u1", "viewer", "token")

    @Test
    fun favourite_and_played_flags_toggle_through_post_and_delete_on_the_user_item_routes() =
        runTest {
            val calls = mutableListOf<Pair<HttpMethod, String>>()
            val client =
                client { request ->
                    assertEquals("token", request.headers["X-Emby-Token"])
                    calls += request.method to request.url.encodedPath
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            try {
                val service = EmbyUserDataService(client)

                assertTrue(service.setFavorite(server, "item-1", favorite = true).isSuccess)
                assertTrue(service.setFavorite(server, "item-1", favorite = false).isSuccess)
                assertTrue(service.setPlayed(server, "item-1", played = true).isSuccess)
                assertTrue(service.setPlayed(server, "item-1", played = false).isSuccess)

                assertEquals(
                    listOf(
                        HttpMethod.Post to "/Users/u1/FavoriteItems/item-1",
                        HttpMethod.Delete to "/Users/u1/FavoriteItems/item-1",
                        HttpMethod.Post to "/Users/u1/PlayedItems/item-1",
                        HttpMethod.Delete to "/Users/u1/PlayedItems/item-1",
                    ),
                    calls,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun snapshot_merges_the_favourite_resumable_and_played_pages_by_item_id() =
        runTest {
            val client =
                client { request ->
                    assertEquals("/Users/u1/Items", request.url.encodedPath)
                    assertEquals("UserData,DateModified", request.url.parameters["Fields"])
                    when {
                        request.url.parameters["Filters"] == "IsFavorite" ->
                            json(
                                """{"Items":[{"Id":"f1","Name":"Fav","UserData":{"IsFavorite":true,"Played":false}}],""" +
                                    """"TotalRecordCount":1}""",
                            )
                        request.url.parameters["Filters"] == "IsResumable" ->
                            json(
                                """{"Items":[{"Id":"r1","Name":"Resume","UserData":{"PlaybackPositionTicks":500}}],""" +
                                    """"TotalRecordCount":1}""",
                            )
                        request.url.parameters["IsPlayed"] == "true" ->
                            json(
                                """{"Items":[""" +
                                    """{"Id":"p1","Name":"Done","UserData":{"Played":true},"DateModified":"2026-09-01"},""" +
                                    """{"Id":"f1","Name":"Fav","UserData":{"IsFavorite":true,"Played":true}}""" +
                                    """],"TotalRecordCount":2}""",
                            )
                        else -> error("unexpected query ${request.url}")
                    }
                }
            try {
                val snapshot = EmbyUserDataService(client).snapshot(server).getOrThrow()

                assertEquals(
                    listOf(
                        SyncedUserItem("f1", "Fav", favorite = true, played = true, positionTicks = 0L),
                        SyncedUserItem("r1", "Resume", favorite = false, played = false, positionTicks = 500L),
                        SyncedUserItem("p1", "Done", favorite = false, played = true, positionTicks = 0L, "2026-09-01"),
                    ),
                    snapshot,
                )
            } finally {
                client.close()
            }
        }

    @Test
    fun a_favourites_only_snapshot_never_asks_for_progress_and_reports_no_playback_state() =
        runTest {
            var requests = 0
            val client =
                client { request ->
                    requests++
                    assertEquals("IsFavorite", request.url.parameters["Filters"])
                    json("""{"Items":[{"Id":"f1","Name":"Fav","UserData":{"IsFavorite":true,"Played":true,"PlaybackPositionTicks":9}}],"TotalRecordCount":1}""")
                }
            try {
                val snapshot =
                    EmbyUserDataService(client)
                        .snapshot(server, includeProgress = false, includeFavorites = true)
                        .getOrThrow()

                assertEquals(1, requests)
                assertEquals(listOf(SyncedUserItem("f1", "Fav", favorite = true, played = false, positionTicks = 0L)), snapshot)
            } finally {
                client.close()
            }
        }

    @Test
    fun a_failed_first_page_fails_the_whole_snapshot_instead_of_reporting_partial_state() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.InternalServerError) }
            try {
                val result = EmbyUserDataService(client).snapshot(server)

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
