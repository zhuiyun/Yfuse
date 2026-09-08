package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidYCoreProxyCloseTest {
    @Test
    fun sequential_and_range_shutdown_cancel_upstream_without_waiting_for_slow_cleanup() {
        listOf(false, true).forEach { range ->
            val upstream = BlockingTransport()
            withProxy({ upstream }) { proxy ->
                val uri = proxy.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null)
                val reader = Executors.newSingleThreadExecutor()
                try {
                    val response =
                        reader.submit<Boolean> {
                            val connection = URL(uri).openConnection() as HttpURLConnection
                            connection.connectTimeout = 2_000
                            connection.readTimeout = 2_000
                            if (range) connection.setRequestProperty("Range", "bytes=0-99")
                            try {
                                connection.inputStream.use { it.read() }
                                false
                            } catch (_: Exception) {
                                true
                            } finally {
                                connection.disconnect()
                            }
                        }
                    assertTrue(upstream.openEntered.await(2L, TimeUnit.SECONDS))
                    assertCloseReturnsBeforeCleanup(proxy, upstream)
                    assertTrue(response.get(2L, TimeUnit.SECONDS), "The accepted client socket must be closed")
                    assertTrue(upstream.requestWasRange == range)
                    assertEquals(
                        "https://media.test/next.mp4",
                        proxy.localUrl(
                            "https://media.test/next.mp4",
                            cacheable = false,
                            cacheIdentity = null,
                        ),
                    )
                } finally {
                    upstream.allowCleanup.countDown()
                    reader.shutdownNow()
                }
            }
        }
    }

    @Test
    fun shutdown_closes_a_client_which_has_not_finished_sending_headers() {
        withProxy({ error("Incomplete request must not open an upstream") }) { proxy ->
            val uri = URI(proxy.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null))
            Socket(uri.host, uri.port).use { client ->
                client.soTimeout = 2_000
                client.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\n".toByteArray())
                client.getOutputStream().flush()
                proxy.close()
                val closed = runCatching { client.getInputStream().read() == -1 }.getOrDefault(true)
                assertTrue(closed)
            }
            assertTrue(runCatching { Socket(uri.host, uri.port).close() }.isFailure)
        }
    }

    @Test
    fun shutdown_cancels_manifest_waiter_even_while_the_worker_is_still_cleaning_up() =
        runBlocking {
            val upstream = BlockingTransport()
            withProxy({ upstream }) { proxy ->
                val uri = proxy.localUrl("https://media.test/master.m3u8", cacheable = false, cacheIdentity = null)
                val resolution = async(Dispatchers.Default) { proxy.resolvePlaybackTarget(uri, 0L) }
                try {
                    assertTrue(upstream.openEntered.await(2L, TimeUnit.SECONDS))
                    assertCloseReturnsBeforeCleanup(proxy, upstream)
                    withTimeout(2_000L) { assertFailsWith<CancellationException> { resolution.await() } }
                } finally {
                    upstream.allowCleanup.countDown()
                }
            }
        }

    @Test
    fun transport_can_reopen_for_range_retry_but_never_open_after_proxy_shutdown() =
        runBlocking {
            val upstream = RecordingTransport()
            val requests = YCoreProxyRequests()
            val transport = YCoreProxyTransport(upstream, requests)
            val request = YMediaTransportRequest("https://media.test/movie.mp4", YSourceProtocol.Https)
            repeat(2) {
                assertEquals(200, transport.open(request).statusCode)
                transport.close()
            }
            assertEquals(2, upstream.opens.get())
            assertEquals(2, upstream.closes.get())
            requests.close()
            assertFailsWith<CancellationException> { transport.open(request) }
            transport.close()
            assertEquals(2, upstream.opens.get(), "Late registration must cancel before delegate.open")
            val late = YCoreProxyTransport(upstream, requests)
            assertFailsWith<CancellationException> { late.open(request) }
            late.close()
            assertEquals(2, upstream.opens.get())
        }

    @Test
    fun unregister_and_registration_racing_shutdown_leave_no_uncancelled_registration() {
        val requests = YCoreProxyRequests()
        val cancelled = AtomicInteger()
        val finished = requests.register { error("Finished request must not be cancelled") }
        finished?.close()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val start = CountDownLatch(1)
            val registering =
                executor.submit {
                    start.await()
                    repeat(200) { requests.register { cancelled.incrementAndGet() } }
                }
            val closing =
                executor.submit {
                    start.await()
                    requests.close()
                }
            start.countDown()
            registering.get(2L, TimeUnit.SECONDS)
            closing.get(2L, TimeUnit.SECONDS)
            requests.close()
            assertEquals(200, cancelled.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertCloseReturnsBeforeCleanup(
        proxy: AndroidYCoreHttpProxy,
        upstream: BlockingTransport,
    ) {
        val caller = Executors.newSingleThreadExecutor()
        try {
            caller.submit { proxy.close() }.get(1L, TimeUnit.SECONDS)
            assertTrue(upstream.closeEntered.await(2L, TimeUnit.SECONDS))
            assertFalse(upstream.cleanupFinished.await(10L, TimeUnit.MILLISECONDS))
        } finally {
            caller.shutdownNow()
        }
    }

    private inline fun withProxy(
        noinline createTransport: () -> YMediaTransport,
        block: (AndroidYCoreHttpProxy) -> Unit,
    ) {
        val directory = Files.createTempDirectory("ycore-proxy-close").toFile()
        val proxy =
            AndroidYCoreHttpProxy(
                userAgent = "Yfuse-test",
                cacheMaximumBytes = 0L,
                createTransport = createTransport,
                cacheDirectory = directory,
                isMeteredNetwork = { false },
            )
        try {
            block(proxy)
        } finally {
            proxy.close()
            directory.deleteRecursively()
        }
    }

    private open class RecordingTransport : YMediaTransport {
        override val supportedProtocols = setOf(YSourceProtocol.Https)
        override val features = emptySet<YTransportFeature>()
        val opens = AtomicInteger()
        val closes = AtomicInteger()

        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
            opens.incrementAndGet()
            return YMediaTransportResponse(200, contentLength = 1L)
        }

        override suspend fun read(
            destination: ByteArray,
            offset: Int,
            length: Int,
        ): Int = -1

        override suspend fun close() {
            closes.incrementAndGet()
        }
    }

    private class BlockingTransport : RecordingTransport() {
        val openEntered = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        val allowCleanup = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        private val abortRead = CountDownLatch(1)

        @Volatile var requestWasRange = false

        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
            requestWasRange = request.range != null
            openEntered.countDown()
            abortRead.await(5L, TimeUnit.SECONDS)
            throw CancellationException("Upstream cancelled")
        }

        override suspend fun close() {
            closeEntered.countDown()
            abortRead.countDown()
            try {
                check(allowCleanup.await(5L, TimeUnit.SECONDS)) { "Test did not release upstream cleanup" }
            } finally {
                cleanupFinished.countDown()
            }
        }
    }
}
