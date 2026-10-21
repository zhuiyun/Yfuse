package com.yfuse.core.stremio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioClientTest {
    @Test
    fun catalog_requests_preserve_extras_and_ignore_only_malformed_entries() =
        runTest {
            val engine =
                MockEngine { request ->
                    assertTrue(request.url.encodedPath.contains("search=Bunny%20movie&skip=0.json"))
                    assertNull(request.headers["X-Emby-Token"])
                    respond(
                        """{"metas":[{"id":"tt1","type":"movie","name":"Bunny","newField":true},{"broken":true},{"id":"tt2","type":"movie","name":"Other"}]}""",
                        headers = headersOf("Content-Type", "application/json"),
                    )
                }
            HttpClient(engine).use { http ->
                val result =
                    StremioClient(http).catalog(
                        "https://plugin.example/manifest.json",
                        StremioCatalog("movie", "all"),
                        linkedMapOf(
                            "search" to "Bunny movie",
                            "skip" to "0",
                        ),
                    )
                assertEquals(listOf("tt1", "tt2"), result.items.map { it.id })
                assertEquals(3, result.receivedCount)
            }
        }

    @Test
    fun oversized_responses_are_rejected() =
        runTest {
            HttpClient(MockEngine { respond(" ".repeat(2 * 1_024 * 1_024 + 1)) }).use { http ->
                assertFailsWith<IllegalArgumentException> {
                    StremioClient(
                        http,
                    ).manifest("https://plugin.example/manifest.json")
                }
            }
        }

    @Test
    fun remote_errors_do_not_echo_secret_configuration_or_html() =
        runTest {
            HttpClient(MockEngine { respond("secret-path reflected", HttpStatusCode.BadGateway) }).use { http ->
                val failure =
                    assertFailsWith<IllegalArgumentException> {
                        StremioClient(http).manifest("https://plugin.example/secret-path/manifest.json")
                    }
                assertEquals("插件请求失败（HTTP 502）", failure.message)
            }
        }

    @Test
    fun mismatched_metadata_is_not_used_for_a_different_film() =
        runTest {
            val response = """{"meta":{"id":"other","type":"movie","name":"Wrong"}}"""
            HttpClient(MockEngine { respond(response) }).use { http ->
                assertNull(StremioClient(http).meta("https://plugin.example/manifest.json", "movie", "tt1"))
            }
        }

    @Test
    fun cancellation_propagates_instead_of_becoming_an_empty_result() =
        runTest {
            HttpClient(MockEngine { throw CancellationException("leaving page") }).use { http ->
                assertFailsWith<CancellationException> {
                    StremioClient(http).streams("https://plugin.example/manifest.json", "movie", "tt1")
                }
            }
        }

    @Test
    fun subtitle_hints_use_the_video_id_and_safe_http_sidecars_only() =
        runTest {
            HttpClient(
                MockEngine { request ->
                    assertTrue(
                        request.url.encodedPath.contains(
                            "/subtitles/series/tt1%3A2%3A3/videoHash=abc&filename=episode%20one.mkv.json",
                        ),
                    )
                    respond(
                        """{"subtitles":[{"id":"en","url":"https://cdn.example/en.srt","lang":"eng"},{"id":"bad","url":"file:///local","lang":"en"}]}""",
                    )
                },
            ).use { http ->
                val result =
                    StremioClient(http).subtitles(
                        "https://plugin.example/manifest.json",
                        "series",
                        "tt1:2:3",
                        linkedMapOf(
                            "videoHash" to "abc",
                            "filename" to "episode one.mkv",
                        ),
                    )
                assertEquals(listOf("eng"), result.map { it.lang })
            }
        }
}
