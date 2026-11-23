package com.yfuse.feature.player

import com.yfuse.core.playback.rewritePlaybackHlsManifest
import com.yfuse.core.playback.shouldProxyPlaybackNetworkUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidPlaybackHttpProxyTest {
    @Test
    fun an_exhausted_stream_refusal_is_network_evidence_until_the_same_source_reads_successfully() {
        val attempts = AtomicInteger()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    if (attempts.incrementAndGet() <= 2) throw IOException("stream was reset: REFUSED_STREAM")
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("available".toResponseBody())
                        .build()
                }.build()
        AndroidPlaybackHttpProxy(null, "evidence-test", 0, upstreamClient = client).use { proxy ->
            val local = proxy.localUrl("http://127.0.0.1:1/episode.mp4")
            assertTrue(readRawProxyResponse(local).first.startsWith("HTTP/1.1 502"))
            assertTrue(proxy.sourceFailureEvidence.refusedRead)
            assertNull(proxy.sourceFailureEvidence.httpStatus)
            assertEquals("available", readProxyBody(local))
            assertFalse(proxy.sourceFailureEvidence.refusedRead)
            assertEquals(3, attempts.get())
        }
    }

    @Test
    fun refused_http2_media_range_opens_again_with_identical_bytes_and_headers() {
        MockWebServer().use { upstream ->
            upstream.protocols = listOf(Protocol.H2_PRIOR_KNOWLEDGE)

            fun refuse() = MockResponse().setHttp2ErrorCode(7).setSocketPolicy(SocketPolicy.RESET_STREAM_AT_START)
            upstream.enqueue(refuse())
            upstream.enqueue(refuse())
            upstream.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 7-9/10").setBody("789"),
            )
            upstream.start()
            val client =
                OkHttpClient
                    .Builder()
                    .protocols(
                        listOf(Protocol.H2_PRIOR_KNOWLEDGE),
                    ).retryOnConnectionFailure(false)
                    .build()
            val request =
                Request
                    .Builder()
                    .url(upstream.url("/episode"))
                    .header("Range", "bytes=7-9")
                    .build()

            fun open() = client.newCall(request).execute()
            assertFailsWith<IOException> { open().use { error("A refused stream produced a response") } }
            AndroidPlaybackHttpProxy(null, "range-test", 0, upstreamClient = client).use { proxy ->
                val (headers, body) =
                    readRawProxyResponse(
                        proxy.localUrl(request.url.toString()),
                        "Range: bytes=7-9\r\n",
                    )
                assertTrue(headers.startsWith("HTTP/1.1 206"))
                assertTrue(headers.contains("Content-Range: bytes 7-9/10", ignoreCase = true))
                assertContentEquals("789".toByteArray(), body)
            }
            assertEquals(3, upstream.requestCount)
            // MockWebServer records refused streams without parsing their request headers.
            repeat(2) { assertNotNull(upstream.takeRequest(2, TimeUnit.SECONDS)) }
            assertEquals("bytes=7-9", upstream.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Range"))
        }
    }

    @Test
    fun cached_refusal_retries_once_and_respects_cancellation_and_other_errors() {
        fun refused() = IOException("Open failed", ExecutionException(IOException("stream was reset: REFUSED_STREAM")))
        var opens = 0
        var retries = 0
        assertFailsWith<IOException> {
            openPlaybackRead(prepareRetry = { retries++ }) {
                opens++
                throw refused()
            }
        }
        assertEquals(2, opens)
        assertEquals(1, retries)
        opens = 0
        val cancelled =
            assertFailsWith<IOException> {
                openPlaybackRead(prepareRetry = { throw IOException("Playback proxy request closed") }) {
                    opens++
                    throw refused()
                }
            }
        assertEquals("Playback proxy request closed", cancelled.message)
        assertEquals(1, opens)
        for (message in listOf("HTTP 403", "stream was reset: CANCEL", "certificate rejected")) {
            val error =
                assertFailsWith<IOException> {
                    openPlaybackRead(prepareRetry = { error("Unexpected retry") }) { throw IOException(message) }
                }
            assertEquals(message, error.message)
        }
    }

    @Test
    fun large_binary_behind_a_playlist_url_keeps_every_byte_despite_misleading_mime() {
        val media = ByteArray(4 * 1024 * 1024 + 17) { (it % 251).toByte() }
        byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()).copyInto(media)
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody(Buffer().write(media)),
            )
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val (headers, body) = readRawProxyResponse(proxy.localUrl(upstream.url("/disguised.m3u8").toString()))
                assertTrue(headers.startsWith("HTTP/1.1 200"))
                assertTrue(headers.contains("Content-Length: ${media.size}"))
                assertContentEquals(media, body)
            }
        }
    }

    @Test
    fun seeking_a_disguised_file_preserves_range_and_an_embedded_playlist_signature() {
        val media = "#EXTM3U\n#EXTINF:6,\nnot-a-real-segment.ts\n".toByteArray()
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", "video/x-matroska")
                    .setHeader("Content-Range", "bytes 1024-${1023 + media.size}/9000000")
                    .setBody(Buffer().write(media)),
            )
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val (headers, body) =
                    readRawProxyResponse(
                        proxy.localUrl(upstream.url("/disguised.m3u8").toString()),
                        "Range: bytes=1024-\r\n",
                    )
                assertTrue(headers.startsWith("HTTP/1.1 206"))
                assertTrue(headers.contains("Content-Range: bytes 1024-${1023 + media.size}/9000000"))
                assertContentEquals(media, body)
                assertEquals("bytes=1024-", upstream.takeRequest(2, TimeUnit.SECONDS)?.getHeader("Range"))
            }
        }
    }

    @Test
    fun playlist_shaped_error_body_is_forwarded_without_rewriting_or_hiding_rejection() {
        val denied = "#EXTM3U\naccess-denied\n"
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(403).setBody(denied))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                assertEquals(denied, readProxyBody(proxy.localUrl(upstream.url("/movie.m3u8").toString()), 403))
                assertEquals(403, proxy.sourceFailureEvidence.httpStatus)
            }
        }
    }

    private fun readRawProxyResponse(
        url: String,
        headers: String = "",
    ): Pair<String, ByteArray> {
        val local = URI(url)
        return Socket(local.host, local.port).use { client ->
            client.soTimeout = 5_000
            client.getOutputStream().write(
                "GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n$headers\r\n".toByteArray(),
            )
            val response = client.getInputStream().readBytes()
            val boundary = response.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n")
            assertTrue(boundary >= 0)
            response.copyOfRange(0, boundary).toString(Charsets.ISO_8859_1) to
                response.copyOfRange(boundary + 4, response.size)
        }
    }

    @Test
    fun rejected_old_routes_cannot_poison_the_next_movie_or_a_fresh_retry() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            upstream.enqueue(MockResponse().setBody("movie bytes"))
            upstream.enqueue(MockResponse().setResponseCode(403).setBody("late denial"))
            upstream.enqueue(MockResponse().setResponseCode(403).setBody("fresh denial"))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val oldSource = upstream.url("/old").toString()
                val oldRoute = proxy.localUrl(oldSource)
                assertEquals("denied", readProxyBody(oldRoute, 403))
                assertEquals(403, proxy.sourceFailureEvidence.httpStatus)
                val current = proxy.localUrl(upstream.url("/next").toString())
                assertNull(proxy.sourceFailureEvidence.httpStatus)
                assertEquals("movie bytes", readProxyBody(current))
                assertEquals("late denial", readProxyBody(oldRoute, 403))
                assertNull(proxy.sourceFailureEvidence.httpStatus)
                assertEquals("fresh denial", readProxyBody(proxy.localUrl(oldSource), 403))
                assertEquals(403, proxy.sourceFailureEvidence.httpStatus)
            }
        }
    }

    @Test
    fun hls_child_denial_belongs_to_the_active_movie() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody("#EXTM3U\n#EXTINF:6,\nsegment.ts\n"))
            upstream.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val manifest = readProxyBody(proxy.localUrl(upstream.url("/movie.m3u8").toString()))
                val segment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
                assertEquals("denied", readProxyBody(segment, 403))
                assertEquals(403, proxy.sourceFailureEvidence.httpStatus)
            }
        }
    }

    @Test
    fun head_preserves_source_length_without_forwarding_a_body() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Content-Length", "12345"))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val local = URI(proxy.localUrl(upstream.url("/media").toString()))
                Socket(local.host, local.port).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(
                        "HEAD ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(),
                    )
                    val response = client.getInputStream().bufferedReader().readText()
                    assertTrue(response.contains("Content-Length: 12345"))
                    assertTrue(response.endsWith("\r\n\r\n"))
                }
                assertEquals("HEAD", upstream.takeRequest(2, TimeUnit.SECONDS)?.method)
            }
        }
    }

    @Test
    fun redirect_cookies_range_and_reused_connection_survive_shared_transport() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setResponseCode(
                        302,
                    ).setHeader("Location", "/media")
                    .setHeader("Set-Cookie", "access=valid; Path=/"),
            )
            upstream.enqueue(MockResponse().setBody("first"))
            upstream.enqueue(
                MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 2-3/5").setBody("rs"),
            )
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                assertEquals("first", readProxyBody(proxy.localUrl(upstream.url("/redirect").toString())))
                val local = URI(proxy.localUrl(upstream.url("/media").toString()))
                Socket(local.host, local.port).use { client ->
                    client.soTimeout = 2_000
                    client.getOutputStream().write(
                        "GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\nRange: bytes=2-3\r\n\r\n".toByteArray(),
                    )
                    val response = client.getInputStream().bufferedReader().readText()
                    assertTrue(response.startsWith("HTTP/1.1 206"))
                    assertTrue(response.endsWith("rs"))
                }
                upstream.takeRequest(2, TimeUnit.SECONDS)
                val redirected = assertNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
                val ranged = assertNotNull(upstream.takeRequest(2, TimeUnit.SECONDS))
                assertTrue(redirected.getHeader("Cookie").orEmpty().contains("access=valid"))
                assertTrue(ranged.getHeader("Cookie").orEmpty().contains("access=valid"))
                assertEquals("bytes=2-3", ranged.getHeader("Range"))
                assertEquals("proxy-test", ranged.getHeader("User-Agent"))
                assertTrue(ranged.sequenceNumber > 0, "The transport should reuse the open upstream connection")
            }
        }
    }

    @Test
    fun rewritten_manifest_segments_are_served_by_the_same_live_proxy() {
        MockWebServer().use { upstream ->
            upstream.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                    .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n"),
            )
            upstream.enqueue(MockResponse().setBody("segment-bytes"))
            upstream.start()
            AndroidPlaybackHttpProxy(context = null, userAgent = "proxy-test", videoCacheBytes = 0).use { proxy ->
                val manifest = readProxyBody(proxy.localUrl(upstream.url("/live.m3u8").toString()))
                val segment = manifest.lineSequence().first { it.startsWith("http://127.0.0.1:") }
                assertEquals("segment-bytes", readProxyBody(segment))
                assertEquals("/live.m3u8", upstream.takeRequest(2, TimeUnit.SECONDS)?.path)
                assertEquals("/segment.ts", upstream.takeRequest(2, TimeUnit.SECONDS)?.path)
            }
        }
    }

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
                    assertTrue(assertNotNull(input.readLine()).startsWith("HTTP/1.1 200 "))
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
        assertTrue(shouldProxyPlaybackNetworkUrl("https://media.example/video.mkv"))
        assertTrue(shouldProxyPlaybackNetworkUrl("http://192.168.1.2/video.m2ts"))
        assertFalse(shouldProxyPlaybackNetworkUrl("file:///storage/video.mkv"))
        assertFalse(shouldProxyPlaybackNetworkUrl("bd://longest"))
        assertFalse(shouldProxyPlaybackNetworkUrl("not a URL"))
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
        assertNull(parsePlaybackHttpByteRange("bytes=0-9223372036854775808"))
        assertNull(parsePlaybackHttpByteRange("bytes=0-${Long.MAX_VALUE}"))
        assertEquals(
            PlaybackHttpByteRange(start = Long.MAX_VALUE, endInclusive = Long.MAX_VALUE),
            parsePlaybackHttpByteRange("bytes=${Long.MAX_VALUE}-${Long.MAX_VALUE}"),
        )
    }

    @Test
    fun hlsVariantsSegmentsAndKeysStayOnThePlatformTransportBridge() {
        val localized = linkedMapOf<String, String>()
        val rewritten =
            rewritePlaybackHlsManifest(
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

    private fun readProxyBody(
        url: String,
        expectedStatus: Int = 200,
    ): String {
        val local = URI(url)
        return Socket(local.host, local.port).use { client ->
            client.soTimeout = 2_000
            client.getOutputStream().write("GET ${local.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
            val input = client.getInputStream().bufferedReader()
            assertTrue(assertNotNull(input.readLine()).startsWith("HTTP/1.1 $expectedStatus "))
            while (!input.readLine().isNullOrEmpty()) Unit
            input.readText()
        }
    }
}
