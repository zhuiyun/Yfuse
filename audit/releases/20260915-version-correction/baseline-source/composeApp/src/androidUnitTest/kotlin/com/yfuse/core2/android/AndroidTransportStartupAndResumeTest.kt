package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidTransportStartupAndResumeTest {
    @Test
    fun `cold seek after reading the header bypasses an unfinished full block prefetch`() {
        val blockBytes = 2 * 1024 * 1024
        val media = ByteArray(blockBytes * 8) { it.toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val releaseFullBlocks = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                initialMediaBitRateBitsPerSecond = 40_000_000L,
                createTransport = { TestRangeTransport(media, requests, fullBlockRelease = releaseFullBlocks) },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertEquals(1, worker.submit<Int> { source.readAt(0L, ByteArray(1), 0, 1) }.get(2, TimeUnit.SECONDS))
            val position = blockBytes * 3L + 37L
            val output = ByteArray(16)
            assertEquals(16, worker.submit<Int> { source.readAt(position, output, 0, 16) }.get(2, TimeUnit.SECONDS))
            assertContentEquals(media.copyOfRange(position.toInt(), position.toInt() + 16), output)
            assertTrue(requests.any { it.range == YByteRange(position, position + 128 * 1024L - 1L) })
        } finally {
            releaseFullBlocks.countDown()
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `unknown length probe returns without downloading the full first block`() {
        verifyStartupRead(position = 0L, probeSize = true)
    }

    @Test
    fun `first cold read at a resume position does not download preceding block bytes`() {
        verifyStartupRead(position = 1024L * 1024L + 37L, probeSize = false)
    }

    private fun verifyStartupRead(
        position: Long,
        probeSize: Boolean,
    ) {
        val media = ByteArray(2 * 1024 * 1024) { it.toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val releaseFullBlock = CountDownLatch(1)
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                createTransport = { TestRangeTransport(media, requests, fullBlockRelease = releaseFullBlock) },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val output = ByteArray(16)
            if (probeSize) {
                assertEquals(media.size.toLong(), worker.submit<Long> { source.getSize() }.get(2, TimeUnit.SECONDS))
            } else {
                assertEquals(16, worker.submit<Int> { source.readAt(position, output, 0, 16) }.get(2, TimeUnit.SECONDS))
                assertContentEquals(media.copyOfRange(position.toInt(), position.toInt() + 16), output)
            }
            val initial = requests.first { it.range?.startInclusive == position }
            assertEquals(position + 128 * 1024L - 1, initial.range?.endInclusive)
            // Allow the background full block now, then cross the end of the startup slice.
            releaseFullBlock.countDown()
            val next = position + 128 * 1024L
            assertEquals(16, worker.submit<Int> { source.readAt(next, output, 0, 16) }.get(2, TimeUnit.SECONDS))
            assertContentEquals(media.copyOfRange(next.toInt(), next.toInt() + 16), output)
        } finally {
            releaseFullBlock.countDown()
            source.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun `retry resumes only remaining bytes under the same strong entity tag`() {
        verifyRetry(entityTag = "\"version-a\"", changedTag = false, expectedStarts = listOf(0L, 48L))
    }

    @Test
    fun `retry without a strong validator restarts the entire block`() {
        verifyRetry(entityTag = null, changedTag = false, expectedStarts = listOf(0L, 0L))
        verifyRetry(entityTag = "W/\"version-a\"", changedTag = false, expectedStarts = listOf(0L, 0L))
    }

    @Test
    fun `changed entity after a partial response cannot splice different versions`() {
        verifyRetry(entityTag = "\"version-a\"", changedTag = true, expectedStarts = listOf(0L, 48L))
    }

    private fun verifyRetry(
        entityTag: String?,
        changedTag: Boolean,
        expectedStarts: List<Long>,
    ) {
        val media = ByteArray(64) { (it + 19).toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val source =
            AndroidTransportMediaDataSource(
                uri = "https://example.invalid/video.mp4",
                protocol = YSourceProtocol.Https,
                headers = emptyMap(),
                blockSizeOverride = 64,
                createTransport = {
                    TestRangeTransport(
                        media,
                        requests,
                        entityTag,
                        failFirstAfter = 48,
                        changedTag = changedTag,
                    )
                },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            if (changedTag) {
                val failure =
                    assertFailsWith<java.util.concurrent.ExecutionException> {
                        worker.submit<Long> { source.getSize() }.get(3, TimeUnit.SECONDS)
                    }
                assertTrue(
                    failure.cause
                        ?.message
                        .orEmpty()
                        .contains("representation changed"),
                )
            } else {
                assertEquals(64L, worker.submit<Long> { source.getSize() }.get(3, TimeUnit.SECONDS))
                val output = ByteArray(64)
                assertEquals(64, worker.submit<Int> { source.readAt(0L, output, 0, 64) }.get(2, TimeUnit.SECONDS))
                assertContentEquals(media, output)
            }
            assertEquals(expectedStarts, requests.map { it.range?.startInclusive })
            val expectedValidator = entityTag?.takeIf { it.startsWith('"') }
            assertEquals(expectedValidator, requests.last().headers["If-Range"])
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }
}

private class TestRangeTransport(
    private val media: ByteArray,
    private val requests: MutableList<YMediaTransportRequest>,
    private val entityTag: String? = "\"version-a\"",
    private val failFirstAfter: Int? = null,
    private val changedTag: Boolean = false,
    private val fullBlockRelease: CountDownLatch? = null,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    private var attempt = 0
    private var position = 0
    private var endExclusive = 0
    private var fullBlock = false

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        val range = checkNotNull(request.range)
        position = range.startInclusive.toInt()
        endExclusive = minOf(checkNotNull(range.endInclusive).toInt() + 1, media.size)
        if (position >= endExclusive) throw IOException("Outside media")
        requests.add(request)
        attempt++
        fullBlock = endExclusive - position >= 2 * 1024 * 1024
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong(),
            acceptedRange = YByteRange(position.toLong(), endExclusive - 1L),
            entityTag = if (changedTag && attempt > 1) "\"version-b\"" else entityTag,
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (fullBlock && fullBlockRelease != null) check(fullBlockRelease.await(5, TimeUnit.SECONDS))
        if (position == endExclusive) return -1
        if (attempt == 1 &&
            failFirstAfter != null &&
            position >= failFirstAfter
        ) {
            throw IOException("Injected disconnection")
        }
        val end = if (attempt == 1 && failFirstAfter != null) minOf(endExclusive, failFirstAfter) else endExclusive
        val count = minOf(length, end - position)
        media.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() = Unit
}
