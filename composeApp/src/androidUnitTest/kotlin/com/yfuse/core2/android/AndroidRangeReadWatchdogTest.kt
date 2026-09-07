package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidRangeReadWatchdogTest {
    @Test
    fun `adaptive transport opening cancellation remains a retryable timeout`() {
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mkv",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                blockSizeOverride = 8,
                rangeReadBudgetMs = 150L,
                createTransport = {
                    AndroidAdaptiveHttpMediaTransport(
                        createCronet = { error("Unavailable in test") },
                        createOkHttp = {
                            object : YMediaTransport {
                                private val closed = CountDownLatch(1)
                                override val supportedProtocols = setOf(YSourceProtocol.Https)
                                override val features = emptySet<YTransportFeature>()

                                override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                                    check(closed.await(2, TimeUnit.SECONDS))
                                    return YMediaTransportResponse(206)
                                }

                                override suspend fun read(
                                    destination: ByteArray,
                                    offset: Int,
                                    length: Int,
                                ) = -1

                                override suspend fun close() {
                                    closed.countDown()
                                }
                            }
                        },
                    )
                },
            )
        val worker =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val error =
                assertFailsWith<java.util.concurrent.ExecutionException> {
                    worker.submit<Int> { source.readAt(0L, ByteArray(8), 0, 8) }.get(2, TimeUnit.SECONDS)
                }
            assertTrue(error.cause is SocketTimeoutException, error.cause.toString())
            assertTrue(isRecoverableMediaReadFailure(error.cause))
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `retry and promotion consume the same total budget`() {
        var nowNs = 0L
        val budget = YRangeReadBudget(30_000) { nowNs }
        nowNs = 12_000_000_000L
        assertEquals(18_000L, budget.remainingMs())
        nowNs = 30_000_000_000L
        assertFailsWith<SocketTimeoutException> { budget.checkRemaining() }
    }

    @Test
    fun `idle exchange is closed and surfaces as timeout`() {
        val transport = WatchdogTransport()
        AndroidRangeReadWatchdog(transport, YRangeReadBudget(), { 50L }, 10).use { guard ->
            assertTrue(transport.closed.await(2, TimeUnit.SECONDS))
            assertFailsWith<SocketTimeoutException> { guard.checkFailure() }
        }
    }

    @Test
    fun `progress extends idle allowance but cannot restart total budget`() {
        val transport = WatchdogTransport()
        AndroidRangeReadWatchdog(transport, YRangeReadBudget(250), { 5_000L }, 10).use { guard ->
            repeat(50) {
                if (!transport.closed.await(10, TimeUnit.MILLISECONDS)) guard.progressed()
            }
            assertTrue(transport.closed.await(1, TimeUnit.SECONDS))
            assertFailsWith<SocketTimeoutException> { guard.checkFailure() }
        }
    }

    @Test
    fun `finished watchdog cannot close a subsequent exchange`() {
        val transport = WatchdogTransport()
        AndroidRangeReadWatchdog(transport, YRangeReadBudget(), { 50L }, 10).close()
        assertFalse(transport.closed.await(100, TimeUnit.MILLISECONDS))
    }
}

private class WatchdogTransport : YMediaTransport {
    val closed = CountDownLatch(1)
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = emptySet<YTransportFeature>()

    override suspend fun open(request: YMediaTransportRequest) = YMediaTransportResponse(206)

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ) = -1

    override suspend fun close() {
        closed.countDown()
    }
}
