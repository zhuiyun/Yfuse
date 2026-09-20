package com.yfuse.core.data

import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyImages
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.createEmbyClient
import com.yfuse.feature.json
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EmbyPathContractTest {
    private val unusualId = "a/b?#% 雪"
    private val encodedId = "a%2Fb%3F%23%25%20%E9%9B%AA"
    private val server = SavedServer("one", "https://host/emby", "Media", "viewer/admin", "viewer", "token")

    @Test
    fun detail_series_and_user_mutations_keep_ids_inside_one_segment() =
        runTest {
            val calls = mutableListOf<HttpRequestData>()
            val client =
                createEmbyClient(
                    appVersion = "test",
                    engine =
                        MockEngine(
                            MockEngineConfig().apply {
                                dispatcher = Dispatchers.Unconfined
                                addHandler { request ->
                                    calls += request
                                    when {
                                        request.method != HttpMethod.Get -> respond("", HttpStatusCode.NoContent)
                                        request.url.encodedPath.endsWith("/Seasons") -> json("""{"Items":[]}""")
                                        else -> json("""{"Id":"movie","Name":"Movie","Type":"Movie"}""")
                                    }
                                }
                            },
                        ),
                )
            try {
                EmbyDetailService(client).itemDetail(server, unusualId).getOrThrow()
                EmbyDetailService(client).seasons(server, unusualId).getOrThrow()
                EmbyUserDataService(client).setFavorite(server, unusualId, favorite = true).getOrThrow()
                EmbyUserDataService(client).setPlayed(server, unusualId, played = false).getOrThrow()

                assertEquals(
                    listOf(
                        "/emby/Users/viewer%2Fadmin/Items/$encodedId",
                        "/emby/Shows/$encodedId/Seasons",
                        "/emby/Users/viewer%2Fadmin/FavoriteItems/$encodedId",
                        "/emby/Users/viewer%2Fadmin/PlayedItems/$encodedId",
                    ),
                    calls.map { it.url.encodedPath },
                )
                calls.forEach {
                    assertEquals("host", it.url.host)
                    assertEquals("", it.url.fragment)
                    assertEquals("token", it.headers["X-Emby-Token"])
                }
                assertEquals("viewer/admin", calls[1].url.parameters["UserId"])
            } finally {
                client.close()
            }
        }

    @Test
    fun remote_subtitle_item_and_provider_ids_do_not_change_the_download_endpoint() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                createEmbyClient(
                    appVersion = "test",
                    engine =
                        MockEngine(
                            MockEngineConfig().apply {
                                dispatcher = Dispatchers.Unconfined
                                addHandler { request ->
                                    seen = request
                                    respond("", HttpStatusCode.NoContent)
                                }
                            },
                        ),
                )
            try {
                EmbySubtitleService(client).download(server, unusualId, "provider/%2F?#").getOrThrow()
                val request = assertNotNull(seen)
                assertEquals(
                    "/emby/Items/$encodedId/RemoteSearch/Subtitles/provider%2F%252F%3F%23",
                    request.url.encodedPath,
                )
                assertEquals("", request.url.fragment)
                assertEquals(emptySet(), request.url.parameters.names())
            } finally {
                client.close()
            }
        }

    @Test
    fun media_and_artwork_builders_preserve_the_same_path_segment_contract() {
        val urls =
            mapOf(
                EmbyStream.directPlay(server.baseUrl, unusualId, "token") to "/Videos/$encodedId/stream",
                EmbyStream.transcode(server.baseUrl, unusualId, "token") to "/Videos/$encodedId/master.m3u8",
                EmbyStream.progressiveTranscode(server.baseUrl, unusualId, "token") to "/Videos/$encodedId/stream.mp4",
                EmbyStream.subtitle(server.baseUrl, unusualId, "source/one", 2, "token") to
                    "/Videos/$encodedId/source%2Fone/Subtitles/2/Stream.srt",
                EmbyStream.videoPreviewThumbnail(server.baseUrl, unusualId, "source", 0, null, "token") to
                    "/Items/$encodedId/Images/Thumbnail",
                assertNotNull(EmbyImages.primary(server.baseUrl, unusualId, null, accessToken = "token")) to
                    "/Items/$encodedId/Images/Primary",
                assertNotNull(EmbyImages.backdropOf(server.baseUrl, unusualId, null, accessToken = "token")) to
                    "/Items/$encodedId/Images/Backdrop/0",
            )
        urls.forEach { (value, path) ->
            val url = Url(value)
            assertEquals("/emby$path", url.encodedPath)
            assertEquals("host", url.host)
            assertEquals("", url.fragment)
            assertEquals("token", url.parameters["api_key"])
        }
    }
}
