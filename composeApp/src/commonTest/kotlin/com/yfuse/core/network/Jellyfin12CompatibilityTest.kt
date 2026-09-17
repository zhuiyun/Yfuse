package com.yfuse.core.network

import com.yfuse.core.data.resolveMediaServerBaseUrl
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Jellyfin12CompatibilityTest {
    @Test
    fun password_login_sends_modern_client_identity_without_a_token() =
        runTest {
            val client =
                createEmbyClient(
                    "1.0",
                    timeouts = null,
                    engine =
                        MockEngine { request ->
                            val authorization = request.headers["Authorization"].orEmpty()
                            assertTrue(authorization.startsWith("MediaBrowser "))
                            assertTrue("DeviceId=" in authorization)
                            assertFalse("Token=" in authorization)
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        },
                )
            try {
                client.get("http://server/System/Info/Public")
            } finally {
                client.close()
            }
        }

    @Test
    fun proxy_authorization_is_preserved_with_modern_query_authentication() =
        runTest {
            val client =
                createEmbyClient(
                    "1.0",
                    timeouts = null,
                    engine =
                        MockEngine { request ->
                            assertEquals("Basic proxy-credential", request.headers["Authorization"])
                            assertEquals("jellyfin-token", request.url.parameters["ApiKey"])
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        },
                )
            try {
                client.get("http://server/Users/user/Views") {
                    header("Authorization", "Basic proxy-credential")
                    header("X-Emby-Token", "jellyfin-token")
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun authenticated_api_works_without_legacy_headers() =
        runTest {
            val client =
                createEmbyClient(
                    "1.0",
                    timeouts = null,
                    engine =
                        MockEngine { request ->
                            val authorization = request.headers["Authorization"].orEmpty()
                            assertTrue(authorization.startsWith("MediaBrowser "))
                            assertTrue("Token=\"jellyfin-token\"" in authorization)
                            respond("{}", headers = headersOf("Content-Type", "application/json"))
                        },
                )
            try {
                client.get("http://server/Users/user/Views") { header("X-Emby-Token", "jellyfin-token") }
            } finally {
                client.close()
            }
        }

    @Test
    fun modern_media_urls_keep_tokens_encoded_and_preserve_emby_compatibility() {
        val token = "a+/= &b"
        val urls =
            listOf(
                EmbyImages.primary("http://server/jellyfin", "item", null, accessToken = token)!!,
                EmbyStream.subtitle("http://server/jellyfin", "item", "source", 2, token),
                EmbyStream.directPlay("http://server/jellyfin", "item", token),
                EmbyStream.trickplayTilePattern("http://server/jellyfin", "item", "source", 320, token),
            )
        urls.forEach { value ->
            val url = Url(value)
            assertEquals(token, url.parameters["ApiKey"])
            assertEquals(token, url.parameters["api_key"])
            assertTrue(url.encodedPath.startsWith("/jellyfin/"))
        }
    }

    @Test
    fun negotiated_modern_token_is_not_duplicated_or_replaced_and_cdn_is_untouched() {
        val url = EmbyStream.negotiatedUrl("http://server", "/Videos/1/stream?ApiKey=issued", "different", "session")!!
        assertEquals(listOf("issued"), Url(url).parameters.getAll("ApiKey"))
        assertFalse(Url(url).parameters.contains("api_key"))
        val cdn = "https://cdn.example/stream?signature=signed"
        assertEquals(cdn, EmbyStream.negotiatedUrl("http://server", cdn, "private", "session"))
    }

    @Test
    fun removed_alias_is_repaired_only_after_jellyfin_public_probe() =
        runTest {
            val paths = mutableListOf<String>()
            val client =
                createEmbyClient(
                    "1.0",
                    timeouts = null,
                    engine =
                        MockEngine { request ->
                            paths += request.url.encodedPath
                            assertEquals(null, request.headers["X-Emby-Token"])
                            if (request.url.encodedPath.startsWith("/proxy/emby/")) {
                                respond("", HttpStatusCode.NotFound)
                            } else {
                                respond(
                                    """{"ProductName":"Jellyfin","Version":"12.0.0"}""",
                                    headers = headersOf("Content-Type", "application/json"),
                                )
                            }
                        },
                )
            try {
                assertEquals("http://server/proxy", client.resolveMediaServerBaseUrl("http://server/proxy/emby/"))
                assertEquals(listOf("/proxy/emby/System/Info/Public", "/proxy/System/Info/Public"), paths)
            } finally {
                client.close()
            }
        }

    @Test
    fun working_proxy_prefix_is_never_stripped() =
        runTest {
            var calls = 0
            val client =
                createEmbyClient(
                    "1.0",
                    timeouts = null,
                    engine =
                        MockEngine {
                            calls++
                            respond(
                                """{"ProductName":"Jellyfin","Version":"12.0.0"}""",
                                headers = headersOf("Content-Type", "application/json"),
                            )
                        },
                )
            try {
                assertEquals("http://server/emby", client.resolveMediaServerBaseUrl("http://server/emby"))
                assertEquals(1, calls)
            } finally {
                client.close()
            }
        }
}
