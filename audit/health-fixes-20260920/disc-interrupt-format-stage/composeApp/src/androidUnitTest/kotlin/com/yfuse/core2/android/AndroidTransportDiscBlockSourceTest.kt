package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidTransportDiscBlockSourceTest {
    @Test
    fun `interrupted range recovers while delayed old transport close cannot close the new range`() {
        assertSignalRetiresOnlyItsTransport(AndroidTransportDiscBlockSource::interruptPendingRead)
    }

    @Test
    fun `cancel signal returns without waiting for transport close and permanent source close remains closed`() {
        assertSignalRetiresOnlyItsTransport(AndroidTransportDiscBlockSource::cancelPendingRead)
    }

    private fun assertSignalRetiresOnlyItsTransport(signal: (AndroidTransportDiscBlockSource) -> Unit) {
        val retired = InterruptibleDiscTransport(delayedFirstClose = true)
        val fresh = InterruptibleDiscTransport(delayedFirstClose = false)
        val created = AtomicInteger()
        val source =
            AndroidTransportDiscBlockSource(
                uri = "https://media.invalid/movie.iso",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                credentials = null,
                createTransport = { if (created.getAndIncrement() == 0) retired else fresh },
                readAheadBytes = DISC_LOGICAL_BLOCK_BYTES,
                maximumCacheBytes = DISC_LOGICAL_BLOCK_BYTES,
            )
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<Int> { source.readBlocks(0, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0) }
            assertTrue(retired.readEntered.await(2, TimeUnit.SECONDS))
            workers.submit { signal(source) }.get(1, TimeUnit.SECONDS)
            assertTrue(retired.firstCloseEntered.await(2, TimeUnit.SECONDS))
            assertEquals(-1, first.get(2, TimeUnit.SECONDS))
            val output = ByteArray(DISC_LOGICAL_BLOCK_BYTES)
            val second = workers.submit<Int> { source.readBlocks(1, 1, output, 0) }
            assertTrue(fresh.readEntered.await(2, TimeUnit.SECONDS))
            retired.allowFirstClose.countDown()
            assertTrue(retired.firstCloseFinished.await(2, TimeUnit.SECONDS))
            assertFalse(fresh.closed.get(), "A late close must retain the old transport identity")
            fresh.allowRead.countDown()
            assertEquals(1, second.get(2, TimeUnit.SECONDS))
            assertContentEquals(ByteArray(DISC_LOGICAL_BLOCK_BYTES) { 7 }, output)
            assertEquals(2, created.get())
            source.close()
            signal(source)
            assertEquals(-1, source.readBlocks(2, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0))
            assertEquals(2, created.get(), "Permanent source close must never create another transport")
        } finally {
            retired.allowRead.countDown()
            retired.allowFirstClose.countDown()
            fresh.allowRead.countDown()
            workers.shutdownNow()
            source.close()
        }
    }

    @Test
    fun `remote ISO reads exact UDF blocks and forwards credentials`() {
        val media = ByteArray(DISC_LOGICAL_BLOCK_BYTES * 4) { index -> (index % 251).toByte() }
        val opened = mutableListOf<YMediaTransportRequest>()
        val credentials = YTransportCredentials.UsernamePassword("viewer", "secret", "domain")
        val source =
            AndroidTransportDiscBlockSource(
                uri = "smb://nas/discs/movie.iso",
                protocol = YSourceProtocol.Smb,
                headers = emptyMap(),
                credentials = credentials,
                createTransport = { DiscMemoryTransport(media, opened) },
            )
        val output = ByteArray(DISC_LOGICAL_BLOCK_BYTES * 2)

        assertEquals(2, source.readBlocks(1, 2, output, 0))
        assertContentEquals(media.copyOfRange(DISC_LOGICAL_BLOCK_BYTES, DISC_LOGICAL_BLOCK_BYTES * 3), output)
        assertEquals(
            YByteRange(0, 256L * 1024L - 1L),
            opened.single().range,
        )
        assertSame(credentials, opened.single().credentials)
        assertEquals(1, source.readBlocks(2, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0))
        assertEquals(1, opened.size, "adjacent UDF read should hit the bounded read-ahead cache")
        source.close()
    }

    @Test
    fun `probe requires a real complete block and closed source fails closed`() {
        val media = ByteArray(DISC_LOGICAL_BLOCK_BYTES * 2)
        val source =
            AndroidTransportDiscBlockSource(
                uri = "https://media.invalid/movie.iso",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                credentials = null,
                createTransport = { DiscMemoryTransport(media, mutableListOf()) },
            )

        assertTrue(source.probeRangeSupport())
        source.close()
        assertEquals(-1, source.readBlocks(0, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0))
    }

    @Test
    fun `transport that never makes progress fails instead of spinning forever`() {
        val source =
            AndroidTransportDiscBlockSource(
                uri = "https://media.invalid/movie.iso",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                credentials = null,
                createTransport = ::ZeroReadDiscTransport,
            )

        assertEquals(-1, source.readBlocks(0, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0))
    }

    @Test
    fun `LBA conversion remains 64 bit above four GiB`() {
        val opened = mutableListOf<YMediaTransportRequest>()
        val source =
            AndroidTransportDiscBlockSource(
                uri = "smb://nas/discs/large.iso",
                protocol = YSourceProtocol.Smb,
                headers = emptyMap(),
                credentials = null,
                createTransport = { SparseDiscTransport(opened) },
            )
        val lba = Int.MAX_VALUE / 2 + 1

        assertEquals(1, source.readBlocks(lba, 1, ByteArray(DISC_LOGICAL_BLOCK_BYTES), 0))
        val expectedStart = lba.toLong() * DISC_LOGICAL_BLOCK_BYTES
        assertTrue(expectedStart > 4L * 1024L * 1024L * 1024L)
        assertEquals((expectedStart / (256L * 1024L)) * (256L * 1024L), opened.single().range?.startInclusive)
    }
}

private class InterruptibleDiscTransport(
    private val delayedFirstClose: Boolean,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
    val readEntered = CountDownLatch(1)
    val allowRead = CountDownLatch(1)
    val firstCloseEntered = CountDownLatch(1)
    val allowFirstClose = CountDownLatch(1)
    val firstCloseFinished = CountDownLatch(1)
    val closed = AtomicBoolean()
    private val closes = AtomicInteger()

    override suspend fun open(request: YMediaTransportRequest) =
        YMediaTransportResponse(
            statusCode = 206,
            contentLength = DISC_LOGICAL_BLOCK_BYTES * 4L,
            acceptedRange = requireNotNull(request.range),
            features = features,
        )

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        readEntered.countDown()
        check(allowRead.await(3, TimeUnit.SECONDS)) { "Test did not release the range read" }
        if (delayedFirstClose) return -1
        if (closed.get()) throw IOException("New transport was closed before its read completed")
        destination.fill(7, offset, offset + length)
        return length
    }

    override suspend fun close() {
        if (delayedFirstClose) {
            if (closes.incrementAndGet() != 1) return
            firstCloseEntered.countDown()
            allowRead.countDown()
            try {
                check(allowFirstClose.await(3, TimeUnit.SECONDS)) { "Test did not release the retired transport close" }
                closed.set(true)
            } finally {
                firstCloseFinished.countDown()
            }
        } else {
            closed.set(true)
            allowRead.countDown()
        }
    }
}

private class DiscMemoryTransport(
    private val media: ByteArray,
    private val opened: MutableList<YMediaTransportRequest>,
) : YMediaTransport {
    override val supportedProtocols = YSourceProtocol.entries.toSet()
    override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
    private var position = 0
    private var endExclusive = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        opened += request
        val range = requireNotNull(request.range)
        if (range.startInclusive >= media.size) {
            return YMediaTransportResponse(statusCode = 206, contentLength = media.size.toLong())
        }
        position = range.startInclusive.toInt()
        endExclusive = minOf((range.endInclusive ?: media.lastIndex.toLong()).toInt() + 1, media.size)
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong(),
            acceptedRange = YByteRange(position.toLong(), (endExclusive - 1).toLong()),
            features = features,
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= endExclusive) return -1
        val count = minOf(length, endExclusive - position)
        media.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() = Unit
}

private class ZeroReadDiscTransport : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)

    override suspend fun open(request: YMediaTransportRequest) =
        YMediaTransportResponse(
            statusCode = 206,
            contentLength = DISC_LOGICAL_BLOCK_BYTES.toLong(),
            acceptedRange = requireNotNull(request.range),
            features = features,
        )

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int = 0

    override suspend fun close() = Unit
}

private class SparseDiscTransport(
    private val opened: MutableList<YMediaTransportRequest>,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Smb)
    override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
    private var remaining = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        opened += request
        val range = requireNotNull(request.range)
        remaining = (requireNotNull(range.endInclusive) - range.startInclusive + 1L).toInt()
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = Long.MAX_VALUE,
            acceptedRange = range,
            features = features,
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (remaining == 0) return -1
        val count = minOf(remaining, length)
        destination.fill(0, offset, offset + count)
        remaining -= count
        return count
    }

    override suspend fun close() = Unit
}
