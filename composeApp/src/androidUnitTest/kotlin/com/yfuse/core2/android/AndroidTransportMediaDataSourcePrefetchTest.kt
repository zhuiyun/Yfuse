package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidTransportMediaDataSourcePrefetchTest {
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
    fun `high bitrate source keeps a bounded multi second prefetch window`() {
        assertEquals(
            8,
            transportPrefetchDepthBlocks(
                blockSize = 2 * 1024 * 1024,
                mediaBitRateBitsPerSecond = 37_932_765L,
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
            assertEquals(8, source.qoeSnapshot().depthBlocks)
            source.setMediaBitRateBitsPerSecond(0L)
            assertEquals(8, source.qoeSnapshot().depthBlocks)
        } finally {
            source.close()
        }
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

private const val TEST_BLOCK_BYTES = 64
