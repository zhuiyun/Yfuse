package com.yfuse.core.widget

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
