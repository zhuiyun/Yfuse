package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidTransportCancellationTest {
    @Test fun cancelling_blocked_read_leaves_source_reusable_for_the_next_seek() {
        verifyCancellation(blockInOpen = true)
    }

    @Test fun cancelling_retry_delay_wakes_reader_and_does_not_poison_future_reads() {
        verifyCancellation(blockInOpen = false)
    }

    private fun verifyCancellation(blockInOpen: Boolean) {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val created = AtomicInteger()
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                blockSizeOverride = 32,
                createTransport = {
                    val first = created.incrementAndGet() == 1
                    object : YMediaTransport {
                        override val supportedProtocols = setOf(YSourceProtocol.Https)
                        override val features = setOf(YTransportFeature.ByteRange)

                        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                            if (first) {
                                entered.countDown()
                                if (blockInOpen) check(cancelled.await(3L, TimeUnit.SECONDS))
                                throw IOException("Injected abandoned exchange")
                            }
                            return YMediaTransportResponse(
                                206,
                                contentLength = 32L,
                                acceptedRange = YByteRange(0L, 31L),
                            )
                        }

                        override suspend fun read(
                            destination: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            repeat(length) { destination[offset + it] = it.toByte() }
                            return length
                        }

                        override suspend fun close() {
                            if (first) cancelled.countDown()
                        }
                    }
                },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val old = worker.submit<Long> { source.getSize() }
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            source.cancelPendingRead()
            assertFailsWith<java.util.concurrent.ExecutionException> { old.get(1L, TimeUnit.SECONDS) }
            source.throwIfReadFailed()
            assertEquals(32L, worker.submit<Long> { source.getSize() }.get(1L, TimeUnit.SECONDS))
            val bytes = ByteArray(32)
            assertEquals(32, worker.submit<Int> { source.readAt(0L, bytes, 0, 32) }.get(1L, TimeUnit.SECONDS))
            assertContentEquals(ByteArray(32) { it.toByte() }, bytes)
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }
}
