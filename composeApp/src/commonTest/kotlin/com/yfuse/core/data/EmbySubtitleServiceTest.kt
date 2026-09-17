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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EmbySubtitleServiceTest {
    private val server = SavedServer("one", "http://host:8096/", "Media", "u1", "viewer", "token")

    @Test
    fun search_asks_the_remote_provider_for_one_language_and_lists_every_candidate() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    json(
                        """
                        [
                            {"Id":"os/1","Name":"Matrix.zh.srt","Language":"zh-CN","Format":"srt","IsHashMatch":true},
                            {"Id":"os/2","Name":"Matrix.eng.srt","Language":"en"}
                        ]
                        """.trimIndent(),
                    )
                }
            try {
                val results = EmbySubtitleService(client).search(server, "item-1", "zh-CN").getOrThrow()

                val request = requireNotNull(seen)
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/Items/item-1/RemoteSearch/Subtitles/zh-CN", request.url.encodedPath)
                assertEquals("false", request.url.parameters["IsPerfectMatch"])
                assertEquals("token", request.headers["X-Emby-Token"])
                assertEquals(listOf("os/1", "os/2"), results.map { it.Id })
                assertEquals(listOf(true, null), results.map { it.IsHashMatch })
            } finally {
                client.close()
            }
        }

    @Test
    fun download_posts_the_provider_subtitle_id_as_one_encoded_path_segment() =
        runTest {
            var seen: HttpRequestData? = null
            val client =
                client { request ->
                    seen = request
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
            try {
                assertTrue(EmbySubtitleService(client).download(server, "item-1", "opensubtitles/123").isSuccess)

                val request = requireNotNull(seen)
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("/Items/item-1/RemoteSearch/Subtitles/opensubtitles%2F123", request.url.encodedPath)
                assertEquals("token", request.headers["X-Emby-Token"])
            } finally {
                client.close()
            }
        }

    @Test
    fun a_missing_subtitle_maps_to_the_not_found_domain_error() =
        runTest {
            val client = client { respond(content = "", status = HttpStatusCode.NotFound) }
            try {
                val result = EmbySubtitleService(client).download(server, "item-1", "gone")

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
