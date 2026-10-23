package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.legacy.componentPlaybackHeaders
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetHeaderScopeTransportTest {
    @Test
    fun arbitrary_component_headers_are_removed_on_redirect_and_cached_redirect_reopen() =
        runBlocking {
            MockWebServer().use { origin ->
                MockWebServer().use { target ->
                    origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/video")))
                    repeat(2) { target.enqueue(MockResponse().setBody("video")) }
                    val state = AndroidHttpMediaRedirectState()
                    val request =
                        YMediaTransportRequest(
                            origin.url("/video").toString(),
                            YSourceProtocol.Http,
                            headers =
                                mapOf(
                                    "X-Private-Key" to "private",
                                    "Referer" to "https://source/?secret",
                                    "User-Agent" to "test",
                                ),
                            originBoundHeaderNames = setOf("x-private-key", "referer"),
                        )
                    repeat(2) {
                        val transport = AndroidHttpMediaTransport(followSafeRedirects = true, redirectState = state)
                        try {
                            assertEquals(200, transport.open(request).statusCode)
                        } finally {
                            transport.close()
                        }
                        val redirected = requireNotNull(target.takeRequest(3, TimeUnit.SECONDS))
                        assertNull(redirected.getHeader("X-Private-Key"))
                        assertNull(redirected.getHeader("Referer"))
                        assertEquals("test", redirected.getHeader("User-Agent"))
                    }
                    assertEquals(1, origin.requestCount, "The second open must exercise cached redirect reuse")
                    assertEquals("private", origin.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Private-Key"))
                }
            }
        }

    @Test
    fun same_origin_redirects_keep_component_headers() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/actual"))
                server.enqueue(MockResponse().setBody("video"))
                val transport = AndroidHttpMediaTransport(followSafeRedirects = true)
                try {
                    transport.open(
                        YMediaTransportRequest(
                            server.url("/entry").toString(),
                            YSourceProtocol.Http,
                            headers = mapOf("X-Private-Key" to "private"),
                            originBoundHeaderNames = setOf("X-Private-Key"),
                        ),
                    )
                    repeat(
                        2,
                    ) { assertEquals("private", server.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Private-Key")) }
                } finally {
                    transport.close()
                }
            }
        }

    @Test
    fun hls_children_drop_all_component_headers_when_leaving_the_root_origin() {
        MockWebServer().use { origin ->
            MockWebServer().use { cdn ->
                origin.enqueue(
                    MockResponse().setBody(
                        listOf(
                            "#EXTM3U",
                            "#EXT-X-TARGETDURATION:6",
                            "#EXT-X-MEDIA-SEQUENCE:0",
                            "#EXTINF:6,",
                            cdn.url("/segment.ts").toString(),
                            "#EXT-X-ENDLIST",
                        ).joinToString("\n"),
                    ),
                )
                cdn.enqueue(MockResponse().setBody("segment"))
                val directory = Files.createTempDirectory("widget-header-scope").toFile()
                try {
                    AndroidYCoreHttpProxy(
                        userAgent = "test",
                        cacheMaximumBytes = 0,
                        cacheDirectory = directory,
                        createTransport = { AndroidHttpMediaTransport(followSafeRedirects = true) },
                        isMeteredNetwork = { false },
                    ).use { proxy ->
                        val local =
                            proxy.localUrl(
                                origin.url("/main.m3u8").toString(),
                                upstreamHeaders =
                                    mapOf(
                                        "X-Private-Key" to "private",
                                        "Referer" to "https://source/?secret",
                                    ),
                                originBoundHeaderNames = setOf("X-Private-Key", "Referer"),
                                cacheable = false,
                                cacheIdentity = null,
                            )
                        val manifest = read(local)
                        val child = manifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
                        assertEquals("segment", read(child))
                        assertEquals("private", origin.takeRequest(3, TimeUnit.SECONDS)?.getHeader("X-Private-Key"))
                        val request = requireNotNull(cdn.takeRequest(3, TimeUnit.SECONDS))
                        assertNull(request.getHeader("X-Private-Key"))
                        assertNull(request.getHeader("Referer"))
                    }
                } finally {
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun range_requests_probe_sources_and_mpv_fallback_keep_the_explicit_header_scope() {
        val names = setOf("X-Private-Key", "Referer")
        val item =
            YMediaItem(
                "widget",
                "https://source/video",
                headers =
                    mapOf(
                        "X-Private-Key" to "private",
                        "Referer" to "ref",
                        "User-Agent" to "test",
                    ),
                originBoundHeaderNames = names,
            )
        assertEquals(names, item.toAndroidSource().originBoundHeaderNames)
        val request = yCoreRandomAccessRequest(item.uri, YSourceProtocol.Https, 0, 100, item.headers, null, names)
        assertEquals(names, request.originBoundHeaderNames)
        assertEquals(mapOf("X-Private-Key" to "private", "Referer" to "ref"), item.componentPlaybackHeaders()?.values)
        assertTrue(request.diagnosticSummary().contains("Https"))
    }

    private fun read(uri: String): String {
        val connection = URL(uri).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
