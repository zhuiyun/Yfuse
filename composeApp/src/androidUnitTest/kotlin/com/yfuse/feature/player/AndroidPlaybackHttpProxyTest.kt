package com.yfuse.feature.player

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlaybackHttpProxyTest {
    @Test
    fun close_does_not_wait_for_an_upstream_that_has_not_sent_response_headers() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            upstream.start()
            val proxy = AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0)
            assertTrue(proxy.isListening)
            val source = upstream.url("/held-video").toString()
            val local = URI(proxy.localUrl(source))
            val closer = Executors.newSingleThreadExecutor()
            try {
                Socket(local.host, local.port).use { client ->
                    client.soTimeout = 1_000
                    client.getOutputStream().write(
                        "GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(),
                    )
                    assertNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
                    closer.submit { proxy.close() }.get(1, TimeUnit.SECONDS)
                    val result = runCatching { client.getInputStream().read() }
                    assertFalse(
                        result.exceptionOrNull() is SocketTimeoutException,
                        "Shutdown must close the client, not leave it waiting",
                    )
                    val read = result.getOrNull()
                    assertTrue(read == null || read == -1, "Cancelled client must receive no invented HTTP response")
                    assertEquals(source, proxy.localUrl(source))
                    // A fresh connection can race the OS accept queue or reach another owner
                    // of the released ephemeral port. Inspect this proxy's listener instead.
                    assertFalse(proxy.isListening, "Shutdown must close the original listening socket")
                }
            } finally {
                proxy.close()
                closer.shutdownNow()
            }
        }
    }

    @Test
    fun close_disconnects_an_incomplete_stream_after_real_response_bytes() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("12345").setHeader("Content-Length", "10"))
            upstream.start()
            val proxy = AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0)
            val local = URI(proxy.localUrl(upstream.url("/partial-video").toString()))
            val closer = Executors.newSingleThreadExecutor()
            try {
                Socket(local.host, local.port).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(
                        "GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(),
                    )
                    val input = client.getInputStream().bufferedReader()
                    assertTrue(assertNotNull(input.readLine()).contains("200"))
                    while (!input.readLine().isNullOrEmpty()) Unit
                    assertEquals("12345", CharArray(5) { input.read().toChar() }.concatToString())
                    closer.submit { proxy.close() }.get(1, TimeUnit.SECONDS)
                    val result = runCatching { input.read() }
                    assertFalse(result.exceptionOrNull() is SocketTimeoutException)
                    val read = result.getOrNull()
                    assertTrue(read == null || read == -1)
                }
            } finally {
                proxy.close()
                closer.shutdownNow()
            }
        }
    }

    @Test
    fun upstream_error_status_is_preserved_when_the_proxy_has_not_been_closed() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(503).setBody("maintenance"))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val local = URI(proxy.localUrl(upstream.url("/unavailable").toString()))
                Socket(local.host, local.port).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(
                        "GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(),
                    )
                    val input = client.getInputStream().bufferedReader()
                    assertTrue(assertNotNull(input.readLine()).contains("503"))
                    while (!input.readLine().isNullOrEmpty()) Unit
                    assertEquals("maintenance", input.readText())
                }
            }
        }
    }

    @Test
    fun onlyWebMediaUsesPlatformTransportBridge() {
        assertTrue(shouldProxyMpvNetworkUrl("https://media.example/video.mkv"))
        assertTrue(shouldProxyMpvNetworkUrl("http://192.168.1.2/video.m2ts"))
        assertFalse(shouldProxyMpvNetworkUrl("file:///storage/video.mkv"))
        assertFalse(shouldProxyMpvNetworkUrl("bd://longest"))
        assertFalse(shouldProxyMpvNetworkUrl("not a URL"))
    }

    @Test
    fun singleByteRangesAreParsedWithoutAcceptingAmbiguousRequests() {
        assertEquals(
            PlaybackHttpByteRange(start = 1024L, endInclusive = 2047L),
            parsePlaybackHttpByteRange("bytes=1024-2047"),
        )
        assertEquals(
            PlaybackHttpByteRange(start = 4096L, endInclusive = null),
            parsePlaybackHttpByteRange("bytes=4096-"),
        )
        assertNull(parsePlaybackHttpByteRange("bytes=-4096"))
        assertNull(parsePlaybackHttpByteRange("bytes=50-10"))
        assertNull(parsePlaybackHttpByteRange("bytes=0-1,4-5"))
    }

    @Test
    fun hlsVariantsSegmentsAndKeysStayOnThePlatformTransportBridge() {
        val localized = linkedMapOf<String, String>()
        val rewritten =
            rewriteMpvHlsManifest(
                manifest =
                    """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="keys/key.bin"
                    #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",URI="audio/track.m3u8"
                    variant/main.m3u8
                    https://cdn.example/segment.ts
                    """.trimIndent(),
                upstreamUrl = "https://emby.example/Videos/item/master.m3u8",
                localize = { upstream ->
                    localized.getOrPut(upstream) { "http://127.0.0.1/local/${localized.size}" }
                },
            )

        assertTrue("http://127.0.0.1/local/0" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/1" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/2" in rewritten, rewritten)
        assertTrue("http://127.0.0.1/local/3" in rewritten, rewritten)
        assertEquals(
            setOf(
                "https://emby.example/Videos/item/keys/key.bin",
                "https://emby.example/Videos/item/audio/track.m3u8",
                "https://emby.example/Videos/item/variant/main.m3u8",
                "https://cdn.example/segment.ts",
            ),
            localized.keys,
        )
    }
}
