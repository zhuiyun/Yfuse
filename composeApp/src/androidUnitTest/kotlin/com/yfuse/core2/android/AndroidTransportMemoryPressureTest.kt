package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidTransportMemoryPressureTest {
    @Test
    fun paused_owner_snapshot_releases_completed_prefetch_and_speculation_resumes_after_recovery() {
        val fixture = Fixture()
        fixture.source.use { source ->
            val output = ByteArray(BLOCK_BYTES)
            assertEquals(output.size, source.readAt(0L, output, 0, output.size))
            awaitBuffered(source)

            fixture.pressure(true)
            val pressured = source.qoeSnapshot()
            assertEquals(0L, pressured.bufferedAheadBytes)
            assertEquals(0, pressured.depthBlocks)
            val before = fixture.opens.get()
            assertEquals(output.size, source.readAt(BLOCK_BYTES.toLong(), output, 0, output.size))
            assertEquals(before + 1, fixture.opens.get(), "Only the demanded range may open under pressure")
            assertContentEquals(ByteArray(BLOCK_BYTES) { it.toByte() }, output)
            assertEquals(0L, source.qoeSnapshot().bufferedAheadBytes)

            fixture.pressure(false)
            assertEquals(output.size, source.readAt(0L, output, 0, output.size))
            awaitBuffered(source)
        }
    }

    @Test
    fun owner_snapshot_discards_the_startup_slice_without_waiting_for_a_future_read() {
        val fixture = Fixture()
        fixture.source.use { source ->
            val output = ByteArray(1)
            assertEquals(1, source.readAt(0L, output, 0, 1))
            awaitBuffered(source)
            fixture.pressure(true)
            assertEquals(0, source.qoeSnapshot().depthBlocks)
            val before = fixture.firstBlockOpens.get()
            assertEquals(1, source.readAt(0L, output, 0, 1))
            assertEquals(before + 1, fixture.firstBlockOpens.get(), "The released startup slice must be fetched again")
        }
    }

    private fun awaitBuffered(source: AndroidTransportMediaDataSource) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2L)
        while (source.qoeSnapshot().bufferedAheadBytes < BLOCK_BYTES && System.nanoTime() < deadline) Thread.yield()
        assertTrue(source.qoeSnapshot().bufferedAheadBytes >= BLOCK_BYTES)
    }

    private class Fixture {
        private val pool = PlaybackMemoryPool(2L * 1024L * 1024L)
        private val speculative = AtomicBoolean(true)
        val opens = AtomicInteger()
        val firstBlockOpens = AtomicInteger()
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/memory-fixture.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                blockSizeOverride = BLOCK_BYTES,
                memoryLeaseOverride = pool.acquire(PlaybackBufferKind.Transport, 2L * 1024L * 1024L),
                allowsSpeculativeWork = speculative::get,
                refreshMemoryPressure = {},
                createTransport = {
                    object : YMediaTransport {
                        override val supportedProtocols = setOf(YSourceProtocol.Https)
                        override val features = setOf(YTransportFeature.ByteRange)
                        private var remaining = 0
                        private var position = 0L

                        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                            opens.incrementAndGet()
                            val range = requireNotNull(request.range)
                            if (range.startInclusive == 0L) firstBlockOpens.incrementAndGet()
                            position = range.startInclusive
                            val end = minOf(requireNotNull(range.endInclusive), FILE_BYTES - 1L)
                            remaining = (end - position + 1L).toInt()
                            return YMediaTransportResponse(
                                206,
                                contentLength = FILE_BYTES,
                                acceptedRange = YByteRange(position, end),
                            )
                        }

                        override suspend fun read(
                            destination: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            if (remaining == 0) return -1
                            val count = minOf(length, remaining)
                            repeat(count) { destination[offset + it] = (position + it).toByte() }
                            remaining -= count
                            position += count
                            return count
                        }

                        override suspend fun close() = Unit
                    }
                },
            )

        fun pressure(value: Boolean) {
            speculative.set(!value)
            pool.setPressure(value)
        }
    }
}

private const val BLOCK_BYTES = 256 * 1024
private const val FILE_BYTES = BLOCK_BYTES * 4L
