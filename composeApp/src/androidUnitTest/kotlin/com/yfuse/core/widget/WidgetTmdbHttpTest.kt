package com.yfuse.core.widget

import com.yfuse.core.network.TmdbAccountAccess
import com.yfuse.core.network.createTmdbClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetTmdbHttpTest {
    @Test fun signed_in_widgets_use_the_existing_proxy_and_keep_authentication_out_of_script_results(): Unit =
        runBlocking {
            var requests = 0
            val engine =
                MockEngine { request ->
                    requests++
                    assertEquals(
                        "https://account.example/api/v1/tmdb/movie/popular?language=zh-CN&page=2",
                        request.url.toString(),
                    )
                    assertEquals("Bearer account-secret", request.headers[HttpHeaders.Authorization])
                    assertEquals(null, request.headers[HttpHeaders.Cookie])
                    respond("""{"results":[]}""", HttpStatusCode.OK, headersOf("X-Internal-Token", "internal-secret"))
                }
            createTmdbClient(
                engine = engine,
                builtInToken = { "tmdb-secret" },
                account =
                    TmdbAccountAccess("https://account.example/api/v1/tmdb", MutableStateFlow(true), {
                        "account-secret"
                    }, { "renewed-secret" }),
            ).use { client ->
                val raw =
                    WidgetTmdbHttp(client).request(
                        buildJsonObject {
                            put("url", "/movie/popular")
                            put(
                                "params",
                                buildJsonObject {
                                    put("language", "zh-CN")
                                    put("page", 2)
                                },
                            )
                            put(
                                "headers",
                                buildJsonObject {
                                    put("Authorization", "untrusted")
                                    put("Cookie", "untrusted")
                                },
                            )
                        },
                    )
                val response = widgetJson.parseToJsonElement(raw).jsonObject
                assertEquals(JsonPrimitive(true), response["ok"])
                assertEquals(JsonObject(emptyMap()), response["headers"])
                assertFalse(raw.contains("secret"))
                assertFalse(raw.contains("account.example"))
            }
            assertEquals(1, requests)
        }

    @Test fun signed_out_widgets_use_only_yfuse_tmdb_authentication(): Unit =
        runBlocking {
            val engine =
                MockEngine { request ->
                    assertEquals("api.themoviedb.org", request.url.host)
                    assertEquals("/3/trending/tv/week", request.url.encodedPath)
                    assertEquals("Bearer own-tmdb-token", request.headers[HttpHeaders.Authorization])
                    respond("""{"results":[]}""", HttpStatusCode.OK)
                }
            createTmdbClient(engine = engine, builtInToken = { "own-tmdb-token" }).use { client ->
                WidgetTmdbHttp(client).request(buildJsonObject { put("url", "/trending/tv/week") })
            }
        }

    @Test fun unsafe_paths_credentials_and_mutations_never_reach_the_network(): Unit =
        runBlocking {
            var calls = 0
            HttpClient(
                MockEngine {
                    calls++
                    respond("{}")
                },
            ).use { client ->
                val bridge = WidgetTmdbHttp(client)
                listOf(
                    "https://example.com",
                    "//example.com",
                    "/movie/../account",
                    "/movie/%2e%2e/account",
                    "/account/1",
                    "/authentication/token/new",
                    "/movie/1?api_key=x",
                    "/movie//1",
                ).forEach { path ->
                    assertFails { bridge.request(buildJsonObject { put("url", path) }) }
                }
                assertFails {
                    bridge.request(
                        buildJsonObject {
                            put("url", "/movie/1")
                            put("method", "POST")
                        },
                    )
                }
                assertFails {
                    bridge.request(
                        buildJsonObject {
                            put("url", "/movie/1")
                            put("params", buildJsonObject { put("session_id", "secret") })
                        },
                    )
                }
                assertFails {
                    bridge.request(
                        buildJsonObject {
                            put("url", "/movie/1")
                            put("params", buildJsonObject { put("filter", buildJsonObject {}) })
                        },
                    )
                }
            }
            assertEquals(0, calls)
        }

    @Test fun error_statuses_remain_errors_and_responses_are_bounded(): Unit =
        runBlocking {
            HttpClient(MockEngine { respond("""{"status_message":"missing"}""", HttpStatusCode.NotFound) }) {
                expectSuccess =
                    true
            }.use { client ->
                val raw = WidgetTmdbHttp(client).request(buildJsonObject { put("url", "/movie/123") })
                val response = widgetJson.parseToJsonElement(raw).jsonObject
                assertEquals(JsonPrimitive(false), response["ok"])
                assertEquals(JsonPrimitive(404), response["status"])
            }
            HttpClient(MockEngine { respond("x".repeat(4 * MAX_WIDGET_SOURCE_BYTES + 1)) }).use { client ->
                assertFails { WidgetTmdbHttp(client).request(buildJsonObject { put("url", "/movie/popular") }) }
            }
        }

    @Test fun cancellation_is_preserved_and_network_errors_do_not_leak_proxy_details(): Unit =
        runBlocking {
            HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
                assertFailsWith<CancellationException> {
                    WidgetTmdbHttp(client).request(buildJsonObject { put("url", "/movie/popular") })
                }
            }
            HttpClient(MockEngine { error("https://account.example/private?token=secret") }).use { client ->
                val error =
                    assertFails { WidgetTmdbHttp(client).request(buildJsonObject { put("url", "/movie/popular") }) }
                assertTrue(error.message.orEmpty().contains("TMDB"))
                assertFalse(error.message.orEmpty().contains("secret"))
            }
        }

    @Test fun the_bridge_timeout_includes_account_routing_and_body_reading(): Unit =
        runBlocking {
            HttpClient(
                MockEngine {
                    delay(2000)
                    respond("{\"results\":[]}")
                },
            ).use { client ->
                val error =
                    assertFailsWith<WidgetRequestException> {
                        WidgetTmdbHttp(client).request(
                            buildJsonObject {
                                put("url", "/movie/popular")
                                put("timeout", 1000)
                            },
                        )
                    }
                assertTrue(error.message.orEmpty().contains("TMDB"))
                assertTrue(error.message.orEmpty().contains("超时"))
            }
        }
}
