package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import com.yfuse.core2.network.YTransportCredentials
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTransportMediaDataSourcePrefetchTest {
    @Test
    fun `random access forwards credentials to every transport request`() {
        val media = ByteArray(128) { it.toByte() }
        val opened = CopyOnWriteArrayList<YMediaTransportRequest>()
        val credentials = YTransportCredentials.UsernamePassword("viewer", "secret", "media")
        val source =
            AndroidTransportMediaDataSource(
                uri = "smb://nas/videos/movie.mkv",
                protocol = YSourceProtocol.Smb,
                headers = emptyMap(),
                credentials = credentials,
                createTransport = { CapturingRangeTransport(media, opened) },
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val output = ByteArray(8)
            assertEquals(8, worker.submit<Int> { source.readAt(0, output, 0, output.size) }.get(2, TimeUnit.SECONDS))
            assertTrue(opened.isNotEmpty())
            assertTrue(opened.all { it.credentials === credentials })
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `sequential read starts the following range before the extractor requests it`() {
        val media = ByteArray(256) { index -> index.toByte() }
        val openedRanges = CopyOnWriteArrayList<Long>()
        val secondBlockOpened = CountDownLatch(1)
        val secondBlockCompleted = CountDownLatch(1)
        val thirdBlockOpened = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = {
                    MemoryRangeTransport(media) { start, completed ->
                        if (!completed) {
                            openedRanges += start
                            if (start == TEST_BLOCK_BYTES.toLong()) secondBlockOpened.countDown()
                            if (start == TEST_BLOCK_BYTES.toLong() * 2L) thirdBlockOpened.countDown()
                        } else if (start == TEST_BLOCK_BYTES.toLong()) {
                            secondBlockCompleted.countDown()
                        }
                    }
                },
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = ByteArray(16)
            assertEquals(16, worker.submit<Int> { source.readAt(0L, first, 0, first.size) }.get(2, TimeUnit.SECONDS))
            assertContentEquals(media.copyOfRange(0, 16), first)
            assertTrue(secondBlockOpened.await(2, TimeUnit.SECONDS))
            assertTrue(secondBlockCompleted.await(2, TimeUnit.SECONDS))
            assertTrue(thirdBlockOpened.await(2, TimeUnit.SECONDS))

            val second = ByteArray(16)
            assertEquals(
                16,
                worker
                    .submit<Int> {
                        source.readAt(TEST_BLOCK_BYTES.toLong(), second, 0, second.size)
                    }.get(2, TimeUnit.SECONDS),
            )
            assertContentEquals(media.copyOfRange(TEST_BLOCK_BYTES, TEST_BLOCK_BYTES + 16), second)
            assertEquals(1, openedRanges.count { it == TEST_BLOCK_BYTES.toLong() })
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `prefetch stops at the known end of the source`() {
        assertTrue(shouldPrefetchTransportBlock(blockIndex = 1L, blockSize = 64, knownSize = -1L))
        assertTrue(shouldPrefetchTransportBlock(blockIndex = 3L, blockSize = 64, knownSize = 256L))
        assertFalse(shouldPrefetchTransportBlock(blockIndex = 4L, blockSize = 64, knownSize = 256L))
    }

    @Test
    fun `foreground range promotes only queued speculative work`() {
        assertTrue(shouldPromoteTransportPrefetch(futureDone = false, executionStarted = false))
        assertFalse(shouldPromoteTransportPrefetch(futureDone = false, executionStarted = true))
        assertFalse(shouldPromoteTransportPrefetch(futureDone = true, executionStarted = true))
    }

    @Test
    fun `queued foreground promotion is included in qoe diagnostics`() {
        val media = ByteArray(TEST_BLOCK_BYTES * 16) { it.toByte() }
        val created = AtomicInteger()
        val activePrefetches = CountDownLatch(4)
        val releasePrefetches = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mkv",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = {
                    if (created.getAndIncrement() == 0) {
                        MemoryRangeTransport(media) { _, _ -> }
                    } else {
                        BlockingPrefetchTransport(media, activePrefetches, releasePrefetches)
                    }
                },
                initialMediaBitRateBitsPerSecond = 40_000_000L,
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, worker.submit<Int> { source.readAt(0L, ByteArray(1), 0, 1) }.get(2, TimeUnit.SECONDS))
            assertTrue(activePrefetches.await(2, TimeUnit.SECONDS))
            assertEquals(
                1,
                worker
                    .submit<Int> {
                        source.readAt(TEST_BLOCK_BYTES.toLong() * 8L, ByteArray(1), 0, 1)
                    }.get(2, TimeUnit.SECONDS),
            )
            assertEquals(1L, source.qoeSnapshot().promotedPrefetchCount)
        } finally {
            source.close()
            releasePrefetches.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `remote media keeps a bounded ten second prefetch window`() {
        assertEquals(
            12,
            transportPrefetchDepthBlocks(
                blockSize = 2 * 1024 * 1024,
                mediaBitRateBitsPerSecond = 37_932_765L,
            ),
        )
        assertEquals(
            11,
            transportPrefetchDepthBlocks(
                blockSize = 2 * 1024 * 1024,
                mediaBitRateBitsPerSecond = 15_221_411L,
            ),
        )
        assertEquals(
            7,
            transportPrefetchDepthBlocks(
                blockSize = 2 * 1024 * 1024,
                mediaBitRateBitsPerSecond = 9_045_792L,
            ),
        )
        assertEquals(
            2,
            transportPrefetchDepthBlocks(
                blockSize = 2 * 1024 * 1024,
                mediaBitRateBitsPerSecond = 0L,
            ),
        )
    }

    @Test
    fun `server bitrate sizes prefetch before extractor opens and missing track bitrate cannot erase it`() {
        val media = ByteArray(256)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mkv",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = { MemoryRangeTransport(media) { _, _ -> } },
                initialMediaBitRateBitsPerSecond = 37_932_765L,
                blockSizeOverride = 2 * 1024 * 1024,
            )
        try {
            assertEquals(12, source.qoeSnapshot().depthBlocks)
            source.setMediaBitRateBitsPerSecond(0L)
            assertEquals(12, source.qoeSnapshot().depthBlocks)
        } finally {
            source.close()
        }
    }

    @Test
    fun `a synchronous range wait exposes buffering state to the playback clock`() {
        val states = CopyOnWriteArrayList<Boolean>()
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = { MemoryRangeTransport(ByteArray(256)) { _, _ -> } },
                onBlockingReadStateChanged = states::add,
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, worker.submit<Int> { source.readAt(0L, ByteArray(1), 0, 1) }.get(2, TimeUnit.SECONDS))
            assertEquals(listOf(true, false), states.take(2))
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `random extractor seek closes cancelled range prefetch transports`() {
        val media = ByteArray(TEST_BLOCK_BYTES * 8) { it.toByte() }
        val created = AtomicInteger()
        val prefetchOpened = CountDownLatch(1)
        val prefetchClosed = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = {
                    if (created.getAndIncrement() == 0) {
                        MemoryRangeTransport(media) { _, _ -> }
                    } else {
                        BlockingPrefetchTransport(media, prefetchOpened, prefetchClosed)
                    }
                },
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, worker.submit<Int> { source.readAt(0L, ByteArray(1), 0, 1) }.get(2, TimeUnit.SECONDS))
            assertTrue(prefetchOpened.await(2, TimeUnit.SECONDS))
            assertEquals(
                1,
                worker
                    .submit<Int> {
                        source.readAt(TEST_BLOCK_BYTES.toLong() * 6L, ByteArray(1), 0, 1)
                    }.get(2, TimeUnit.SECONDS),
            )
            assertTrue(prefetchClosed.await(2, TimeUnit.SECONDS))
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `blocked foreground read is observable while the range is still in flight`() {
        val media = ByteArray(TEST_BLOCK_BYTES * 4) { it.toByte() }
        val foregroundReadStarted = CountDownLatch(1)
        val releaseForegroundRead = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mkv",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = {
                    MemoryRangeTransport(media) { start, completed ->
                        if (!completed && start == 0L) {
                            foregroundReadStarted.countDown()
                            releaseForegroundRead.await(5, TimeUnit.SECONDS)
                        }
                    }
                },
                blockSizeOverride = TEST_BLOCK_BYTES,
            )
        val worker = Executors.newSingleThreadExecutor()
        val sampler = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<Int> { source.readAt(0L, ByteArray(16), 0, 16) }
            assertTrue(foregroundReadStarted.await(2, TimeUnit.SECONDS))

            // readAt holds this instance's monitor for the whole fetch, and qoeSnapshot() is
            // refreshed on the MediaExtractor owner - the same thread that blocks - so neither can
            // report a stall while it happens. NativeDirect's pump reads this accessor directly, so
            // it must stay lock-free: the get() bound below is the assertion.
            val blockedMs =
                sampler.submit<Long> {
                    var value = source.blockedForegroundReadMs()
                    while (value == 0L) value = source.blockedForegroundReadMs()
                    value
                }
            assertTrue(blockedMs.get(2, TimeUnit.SECONDS) > 0L)

            releaseForegroundRead.countDown()
            assertEquals(16, read.get(5, TimeUnit.SECONDS))
            assertEquals(0L, source.blockedForegroundReadMs())
        } finally {
            releaseForegroundRead.countDown()
            source.close()
            sampler.shutdownNow()
            worker.shutdownNow()
        }
    }
}

private class BlockingPrefetchTransport(
    private val media: ByteArray,
    private val opened: CountDownLatch,
    private val closed: CountDownLatch,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    private var start = 0L
    private var end = 0L

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        val range = requireNotNull(request.range)
        start = range.startInclusive
        end = minOf(range.endInclusive ?: media.lastIndex.toLong(), media.lastIndex.toLong())
        opened.countDown()
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong(),
            acceptedRange = YByteRange(start, end),
        )
    }

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        closed.await(5, TimeUnit.SECONDS)
        return -1
    }

    override suspend fun close() {
        closed.countDown()
    }
}

private class MemoryRangeTransport(
    private val media: ByteArray,
    private val onRange: (start: Long, completed: Boolean) -> Unit,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    private var start = 0
    private var endExclusive = 0
    private var position = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        val range = requireNotNull(request.range)
        start = range.startInclusive.toInt()
        position = start
        endExclusive = minOf((range.endInclusive ?: media.lastIndex.toLong()).toInt() + 1, media.size)
        onRange(start.toLong(), false)
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong(),
            acceptedRange = YByteRange(start.toLong(), (endExclusive - 1).toLong()),
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
        if (position == endExclusive) onRange(start.toLong(), true)
        return count
    }

    override suspend fun close() = Unit
}

private class CapturingRangeTransport(
    private val media: ByteArray,
    private val opened: MutableList<YMediaTransportRequest>,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Smb)
    override val features = setOf(YTransportFeature.ByteRange)
    private var position = 0
    private var endExclusive = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        opened += request
        val range = requireNotNull(request.range)
        position = range.startInclusive.toInt()
        endExclusive = minOf((range.endInclusive ?: media.lastIndex.toLong()).toInt() + 1, media.size)
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong(),
            acceptedRange = YByteRange(position.toLong(), (endExclusive - 1).toLong()),
        )
    }

    override suspend fun read(destination: ByteArray, offset: Int, length: Int): Int {
        if (position >= endExclusive) return -1
        val count = minOf(length, endExclusive - position)
        media.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() = Unit
}

private const val TEST_BLOCK_BYTES = 64
