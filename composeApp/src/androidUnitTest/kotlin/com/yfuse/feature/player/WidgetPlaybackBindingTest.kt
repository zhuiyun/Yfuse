package com.yfuse.feature.player

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.source.MediaSource
import com.yfuse.core.playback.PlayerCustomHeaders
import com.yfuse.core.playback.WidgetPlaybackRequest
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import sun.misc.Unsafe
import java.lang.reflect.Proxy
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class WidgetPlaybackBindingTest {
    @Test
    fun exo_sources_retain_independent_clients_when_the_same_url_is_preloaded_concurrently() {
        MockWebServer().use { upstream ->
            val entered = CountDownLatch(2)
            upstream.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        entered.countDown()
                        check(entered.await(3, TimeUnit.SECONDS)) { "Both media sources must load concurrently" }
                        return MockResponse().setBody(request.getHeader("X-Private-Key").orEmpty())
                    }
                }
            val url = upstream.url("/same-video.mp4").toString()
            val first = binding(url, "first")
            val second = binding(url, "second")
            val clients = mutableMapOf<MediaSource, okhttp3.OkHttpClient>()
            val factory =
                WidgetMediaSourceFactory(fakeFactory { error("Unbound media source") }) { bound ->
                    val client = widgetPlaybackHttpClient(bound)
                    fakeFactory { media ->
                        fakeSource(media).also { clients[it] = client }
                    }
                }
            val firstMedia = taggedMedia(first)
            val secondMedia = taggedMedia(second)
            val firstSource = factory.createMediaSource(firstMedia)
            val secondSource = factory.createMediaSource(secondMedia)
            assertSame(firstMedia, firstSource.mediaItem)
            assertSame(secondMedia, secondSource.mediaItem)
            val workers = Executors.newFixedThreadPool(2)
            try {
                val reads =
                    listOf(firstSource, secondSource).map { source ->
                        workers.submit<String> {
                            clients.getValue(source).newCall(Request.Builder().url(url).build()).execute().use {
                                check(it.isSuccessful)
                                it.body!!.string()
                            }
                        }
                    }
                assertEquals(listOf("first", "second"), reads.map { it.get(5, TimeUnit.SECONDS) })
            } finally {
                workers.shutdownNow()
            }
        }
    }

    @Test
    fun proxy_routes_and_their_hls_children_keep_their_own_credentials_after_queue_switch() {
        MockWebServer().use { upstream ->
            upstream.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/same.m3u8") {
                            MockResponse()
                                .setHeader("Content-Type", "application/vnd.apple.mpegurl")
                                .setBody("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nsegment.ts\n#EXT-X-ENDLIST\n")
                        } else {
                            MockResponse().setBody(request.getHeader("X-Private-Key").orEmpty())
                        }
                }
            val url = upstream.url("/same.m3u8").toString()
            AndroidPlaybackHttpProxy(null, "test", 0).use { proxy ->
                val first = proxy.localUrl(url, widgetRequest = binding(url, "first"))
                val second = proxy.localUrl(url, widgetRequest = binding(url, "second"))
                assertNotEquals(first, second)
                val manifests = listOf(first, second).map(::read)
                val segments =
                    manifests.map {
                        it.lineSequence().first { line ->
                            line.startsWith("http://127.0.0.1:")
                        }
                    }
                assertNotEquals(segments[0], segments[1])
                val workers = Executors.newFixedThreadPool(2)
                try {
                    val reads = segments.map { uri -> workers.submit<String> { read(uri) } }
                    assertEquals(listOf("first", "second"), reads.map { it.get(5, TimeUnit.SECONDS) })
                } finally {
                    workers.shutdownNow()
                }
            }
        }
    }

    @Test
    fun proxy_cookies_from_one_item_cannot_replace_another_items_explicit_cookie() {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setHeader("Set-Cookie", "session=first; Path=/").setBody("one"))
            upstream.enqueue(MockResponse().setBody("two"))
            val url = upstream.url("/same.mp4").toString()
            AndroidPlaybackHttpProxy(null, "test", 0).use { proxy ->
                val first = proxy.localUrl(url, widgetRequest = binding(url, "first"))
                val second =
                    proxy.localUrl(
                        url,
                        widgetRequest =
                            WidgetPlaybackRequest(
                                url,
                                PlayerCustomHeaders(
                                    mapOf(
                                        "Cookie" to "session=second",
                                    ),
                                ),
                            ),
                    )
                read(first)
                read(second)
                upstream.takeRequest(3, TimeUnit.SECONDS)
                assertEquals("session=second", upstream.takeRequest(3, TimeUnit.SECONDS)?.getHeader("Cookie"))
            }
        }
    }

    private fun binding(
        url: String,
        value: String,
    ) = WidgetPlaybackRequest(
        url,
        PlayerCustomHeaders(
            mapOf(
                "X-Private-Key" to value,
            ),
        ),
    )

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

    // Keep the real MediaSource factory boundary; only Android Uri construction is replaced.
    private fun taggedMedia(binding: WidgetPlaybackRequest): MediaItem {
        val unsafe =
            Unsafe::class.java
                .getDeclaredField("theUnsafe")
                .apply { isAccessible = true }
                .get(null) as Unsafe
        val configuration =
            unsafe.allocateInstance(
                MediaItem.LocalConfiguration::class.java,
            ) as MediaItem.LocalConfiguration
        MediaItem.LocalConfiguration::class.java
            .getDeclaredField("tag")
            .apply {
                isAccessible = true
            }.set(configuration, binding)
        return MediaItem.Builder().build().also { media ->
            MediaItem::class.java
                .getDeclaredField(
                    "localConfiguration",
                ).apply { isAccessible = true }
                .set(media, configuration)
        }
    }

    private fun fakeFactory(create: (MediaItem) -> MediaSource): MediaSource.Factory =
        Proxy.newProxyInstance(
            MediaSource.Factory::class.java.classLoader,
            arrayOf(MediaSource.Factory::class.java),
        ) { _, method, args ->
            check(method.name == "createMediaSource")
            create(args!![0] as MediaItem)
        } as MediaSource.Factory

    private fun fakeSource(media: MediaItem): MediaSource =
        Proxy.newProxyInstance(
            MediaSource::class.java.classLoader,
            arrayOf(MediaSource::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getMediaItem" -> media
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                "toString" -> "BoundMediaSource"
                else -> error("Unexpected MediaSource call: ${method.name}")
            }
        } as MediaSource
}
