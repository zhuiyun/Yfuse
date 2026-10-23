package com.yfuse.core.widget

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetHttpTest {
    @Test
    fun http_bridge_preserves_params_and_uses_only_explicit_headers(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody("{\"results\":[1]}").setHeader("Content-Type", "application/json"),
                )
                val raw =
                    WidgetHttp.request(
                        buildJsonObject {
                            put("url", server.url("/catalog").toString())
                            put(
                                "params",
                                buildJsonObject {
                                    put("query", "测试 & page")
                                    put("page", 2)
                                },
                            )
                            put("headers", buildJsonObject { put("X-Widget", "example") })
                        },
                    )
                val result = widgetJson.parseToJsonElement(raw).jsonObject
                assertTrue(result["data"] is JsonObject)
                assertEquals("200", result["status"]?.jsonPrimitive?.content)
                val recorded = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("测试 & page", recorded.requestUrl?.queryParameter("query"))
                assertEquals("example", recorded.getHeader("X-Widget"))
                assertNull(recorded.getHeader("Authorization"))
                assertNull(recorded.getHeader("Cookie"))
            }
        }

    @Test
    fun post_and_text_error_responses_are_available_to_scripts(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(403).setBody("Access denied"))
                val raw =
                    WidgetHttp.request(
                        buildJsonObject {
                            put("url", server.url("/login").toString())
                            put("method", "POST")
                            put("data", buildJsonObject { put("query", "movie") })
                        },
                    )
                val result = widgetJson.parseToJsonElement(raw).jsonObject
                assertEquals(JsonPrimitive(false), result["ok"])
                assertEquals(JsonPrimitive("Access denied"), result["data"])
                val recorded = requireNotNull(server.takeRequest(3, TimeUnit.SECONDS))
                assertEquals("POST", recorded.method)
                assertEquals("{\"query\":\"movie\"}", recorded.body.readUtf8())
            }
        }

    @Test
    fun downloads_reject_http_errors_and_oversized_script_bodies(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
                assertFailsWith<IllegalArgumentException> { WidgetHttp.source(server.url("/missing.js").toString()) }
                server.enqueue(MockResponse().setBody("x".repeat(MAX_WIDGET_SOURCE_BYTES + 1)))
                assertFailsWith<IllegalArgumentException> { WidgetHttp.source(server.url("/large.js").toString()) }
            }
        }

    @Test
    fun tmdb_bridge_rejects_external_destinations_before_attaching_its_token(): Unit =
        runBlocking {
            listOf("https://example.com/steal", "//example.com/steal").forEach { url ->
                assertFailsWith<IllegalArgumentException> {
                    WidgetHttp.request(
                        buildJsonObject { put("url", url) },
                        tmdb = true,
                    )
                }
            }
            assertFailsWith<IllegalArgumentException> { WidgetHttp.source("file:///private/script.js") }
        }

    @Test
    fun streamed_files_are_bounded_even_without_a_declared_content_length() {
        assertEquals("abc", ByteArrayInputStream("abc".toByteArray()).readBytesBounded(3).decodeToString())
        assertFailsWith<IllegalArgumentException> { ByteArrayInputStream("abcd".toByteArray()).readBytesBounded(3) }
    }

    @Test
    fun interrupted_reads_and_gateway_errors_recover_once_without_authentication(): Unit =
        runBlocking {
            for (interrupted in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.enqueue(
                        if (interrupted) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else {
                            MockResponse().setResponseCode(503).setBody("unavailable")
                        },
                    )
                    server.enqueue(MockResponse().setBody("{\"items\":[1]}"))
                    val result =
                        WidgetHttp.request(
                            buildJsonObject {
                                put("url", server.url("/feed?language=zh-CN").toString())
                                put("timeout", 3000)
                                put("headers", buildJsonObject { put("X-Widget", "fixture") })
                            },
                        )
                    assertEquals("200", widgetJson.parseToJsonElement(result).jsonObject.text("status"))
                    assertEquals(2, server.requestCount)
                    repeat(2) {
                        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                        assertEquals("/feed?language=zh-CN", request.path)
                        assertEquals("fixture", request.getHeader("X-Widget"))
                        assertNull(request.getHeader("Authorization"))
                        assertNull(request.getHeader("Cookie"))
                    }
                }
            }
        }

    @Test
    fun writes_and_permanent_http_failures_are_never_replayed(): Unit =
        runBlocking {
            for ((method, status) in listOf("POST" to 503, "GET" to 401, "GET" to 404, "GET" to 429)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(status).setBody("failure"))
                    val result =
                        WidgetHttp.request(
                            buildJsonObject {
                                put("url", server.url("/resource").toString())
                                put("method", method)
                            },
                        )
                    assertEquals(status.toString(), widgetJson.parseToJsonElement(result).jsonObject.text("status"))
                    assertEquals(1, server.requestCount)
                }
            }
        }

    @Test
    fun immediate_503_followups_share_the_single_read_retry_budget_and_preserve_the_response(): Unit =
        runBlocking {
            for (method in listOf("GET", "HEAD")) {
                MockWebServer().use { server ->
                    repeat(2) { index ->
                        server.enqueue(
                            MockResponse()
                                .setResponseCode(503)
                                .setHeader("Retry-After", "0")
                                .setHeader("X-Upstream", "attempt-$index")
                                .setBody("unavailable-$index"),
                        )
                    }
                    server.enqueue(MockResponse().setBody("unexpected extra replay"))
                    val result =
                        widgetJson
                            .parseToJsonElement(
                                WidgetHttp.request(
                                    buildJsonObject {
                                        put("url", server.url("/feed").toString())
                                        put("method", method)
                                        put("timeout", 3000)
                                    },
                                ),
                            ).jsonObject
                    assertEquals("503", result.text("status"))
                    assertEquals(2, server.requestCount)
                    assertEquals(
                        "0",
                        result["headers"]
                            ?.jsonObject
                            ?.get("retry-after")
                            ?.jsonArray
                            ?.single()
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(
                        "attempt-1",
                        result["headers"]
                            ?.jsonObject
                            ?.get("x-upstream")
                            ?.jsonArray
                            ?.single()
                            ?.jsonPrimitive
                            ?.content,
                    )
                    if (method == "GET") assertEquals("unavailable-1", result["data"]?.jsonPrimitive?.content)
                }
            }
        }

    @Test
    fun writes_and_initial_408_responses_never_follow_an_automatic_retry_instruction(): Unit =
        runBlocking {
            val cases =
                listOf("POST", "PUT", "PATCH", "DELETE").flatMap { method -> listOf(method to 503, method to 408) } +
                    ("GET" to 408)
            for ((method, status) in cases) {
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setResponseCode(status).setHeader("Retry-After", "0").setBody("original error"),
                    )
                    server.enqueue(MockResponse().setBody("unexpected replay"))
                    val result =
                        widgetJson
                            .parseToJsonElement(
                                WidgetHttp.request(
                                    buildJsonObject {
                                        put("url", server.url("/resource").toString())
                                        put("method", method)
                                        put("data", "payload")
                                        put("timeout", 3000)
                                    },
                                ),
                            ).jsonObject
                    assertEquals(status.toString(), result.text("status"))
                    assertEquals(JsonPrimitive("original error"), result["data"])
                    assertEquals(
                        "0",
                        result["headers"]
                            ?.jsonObject
                            ?.get("retry-after")
                            ?.jsonArray
                            ?.single()
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(1, server.requestCount)
                    val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals(method, request.method)
                    if (method != "GET") assertEquals("payload", request.body.readUtf8())
                }
            }
        }

    @Test
    fun mutation_bodies_are_single_use_without_changing_their_type_length_or_payload(): Unit =
        runBlocking {
            for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse().setResponseCode(307).setHeader("Location", server.url("/redirected")),
                    )
                    server.enqueue(MockResponse().setBody("unexpected body replay"))
                    val payload = "{\"query\":\"测试电影\"}"
                    val contentType = "application/vnd.yfuse.widget+json; charset=utf-8"
                    val response =
                        widgetJson
                            .parseToJsonElement(
                                WidgetHttp.request(
                                    buildJsonObject {
                                        put("url", server.url("/mutation").toString())
                                        put("method", method)
                                        put("data", payload)
                                        put("headers", buildJsonObject { put("Content-Type", contentType) })
                                        put("timeout", 3000)
                                    },
                                ),
                            ).jsonObject
                    assertEquals("307", response.text("status"))
                    assertEquals(1, server.requestCount)
                    val sent = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                    assertEquals(method, sent.method)
                    assertEquals(contentType, sent.getHeader("Content-Type"))
                    assertEquals(payload.toByteArray(Charsets.UTF_8).size.toString(), sent.getHeader("Content-Length"))
                    assertEquals(payload, sent.body.readUtf8())
                }
            }
        }

    @Test
    fun a_503_then_disconnect_or_408_does_not_start_a_third_request(): Unit =
        runBlocking {
            for (disconnect in listOf(false, true)) {
                MockWebServer().use { server ->
                    server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                    server.enqueue(
                        if (disconnect) {
                            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        } else {
                            MockResponse()
                                .setResponseCode(
                                    408,
                                ).setHeader("Retry-After", "0")
                                .setBody("timed out upstream")
                        },
                    )
                    server.enqueue(MockResponse().setBody("unexpected third request"))
                    val options =
                        buildJsonObject {
                            put("url", server.url("/feed").toString())
                            put("timeout", 3000)
                        }
                    if (disconnect) {
                        assertFailsWith<WidgetRequestException> { WidgetHttp.request(options) }
                    } else {
                        val result = widgetJson.parseToJsonElement(WidgetHttp.request(options)).jsonObject
                        assertEquals("408", result.text("status"))
                        assertEquals(JsonPrimitive("timed out upstream"), result["data"])
                    }
                    assertEquals(2, server.requestCount)
                }
            }
        }

    @Test
    fun repeated_disconnects_allow_two_read_requests_and_only_one_write_request(): Unit =
        runBlocking {
            for (method in listOf("GET", "POST", "PUT", "PATCH", "DELETE")) {
                MockWebServer().use { server ->
                    repeat(2) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
                    server.enqueue(MockResponse().setBody("unexpected replay"))
                    assertFailsWith<WidgetRequestException> {
                        WidgetHttp.request(
                            buildJsonObject {
                                put("url", server.url("/resource").toString())
                                put("method", method)
                                put("data", "payload")
                                put("timeout", 3000)
                            },
                        )
                    }
                    assertEquals(if (method == "GET") 2 else 1, server.requestCount)
                }
            }
        }

    @Test
    fun retry_after_metadata_does_not_leak_between_calls_or_recovery_attempts(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setResponseCode(503).setBody("final failure without retry header"))
                val options =
                    buildJsonObject {
                        put("url", server.url("/feed").toString())
                        put("timeout", 3000)
                    }
                val failed = widgetJson.parseToJsonElement(WidgetHttp.request(options)).jsonObject
                assertEquals("503", failed.text("status"))
                assertNull(failed["headers"]?.jsonObject?.get("retry-after"))
                assertEquals(JsonPrimitive("final failure without retry header"), failed["data"])
                server.enqueue(MockResponse().setHeader("Retry-After", "7").setBody("new call"))
                val succeeded = widgetJson.parseToJsonElement(WidgetHttp.request(options)).jsonObject
                assertEquals("200", succeeded.text("status"))
                assertEquals(
                    "7",
                    succeeded["headers"]
                        ?.jsonObject
                        ?.get("retry-after")
                        ?.jsonArray
                        ?.single()
                        ?.jsonPrimitive
                        ?.content,
                )
                assertEquals(3, server.requestCount)
            }
        }

    @Test
    fun caller_timeout_covers_response_body_and_recovery_and_returns_only_a_short_error(): Unit =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("{} ").setBodyDelay(3, TimeUnit.SECONDS))
                val started = System.nanoTime()
                val error =
                    assertFailsWith<WidgetRequestException> {
                        WidgetHttp.request(
                            buildJsonObject {
                                put("url", server.url("/feed?token=private-fixture").toString())
                                put("timeout", 1000)
                            },
                        )
                    }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500)
                assertTrue(error.message.orEmpty().contains("超时"))
                assertFalse(error.message.orEmpty().contains("private-fixture"))
                assertFalse(error.message.orEmpty().contains("http"))
                assertEquals(1, server.requestCount)
            }
        }
}
