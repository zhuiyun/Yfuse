package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidTransportRangeBoundaryTest {
    @Test
    fun `unknown length tail finishes at the accepted range without another body read`() {
        verifyTail(initialRead = false, disconnectAfter = null)
    }

    @Test
    fun `known length tail request ends at the last file byte`() {
        verifyTail(initialRead = true, disconnectAfter = null)
    }

    @Test
    fun `disconnected tail resumes remaining bytes and never requests beyond EOF`() {
        verifyTail(initialRead = false, disconnectAfter = 5)
    }

    @Test
    fun `bounded response with unknown total length needs no extra EOF read`() {
        val media = ByteArray(128) { it.toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val source = source(media, requests, reportLength = false)
        onWorker(source) {
            val output = ByteArray(64)
            assertEquals(64, source.readAt(0L, output, 0, output.size))
            assertContentEquals(media.copyOfRange(0, 64), output)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `premature EOF before the accepted tail remains a read failure`() {
        val media = ByteArray(77) { it.toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val source = source(media, requests, truncateAfter = 5)
        onWorker(source) {
            val failure = assertFailsWith<YRangeReadException> { source.readAt(64L, ByteArray(13), 0, 13) }
            assertTrue(failure.message.orEmpty().contains("ended before"))
            assertTrue(requests.size > 1)
            assertTrue(requests.all { checkNotNull(it.range).startInclusive < media.size })
        }
    }

    private fun verifyTail(
        initialRead: Boolean,
        disconnectAfter: Int?,
    ) {
        val media = ByteArray(77) { (it + 19).toByte() }
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        val source = source(media, requests, disconnectAfter = disconnectAfter)
        onWorker(source) {
            if (initialRead) assertEquals(1, source.readAt(0L, ByteArray(1), 0, 1))
            val output = ByteArray(64)
            assertEquals(13, source.readAt(64L, output, 0, output.size))
            assertContentEquals(media.copyOfRange(64, 77), output.copyOf(13))
            val tail = requests.filter { checkNotNull(it.range).startInclusive >= 64L }
            assertEquals(
                if (disconnectAfter ==
                    null
                ) {
                    listOf(64L)
                } else {
                    listOf(64L, 69L)
                },
                tail.map { it.range?.startInclusive },
            )
            if (initialRead) assertEquals(76L, tail.first().range?.endInclusive)
            if (disconnectAfter != null) {
                assertEquals(76L, tail.last().range?.endInclusive)
                assertEquals("\"version-a\"", tail.last().headers["If-Range"])
            }
            assertEquals(77L, source.getSize())
            val requestCount = requests.size
            assertEquals(-1, source.readAt(77L, output, 0, output.size))
            assertEquals(-1, source.readAt(90L, output, 0, output.size))
            assertEquals(requestCount, requests.size)
        }
    }

    private fun source(
        media: ByteArray,
        requests: MutableList<YMediaTransportRequest>,
        reportLength: Boolean = true,
        disconnectAfter: Int? = null,
        truncateAfter: Int? = null,
    ) = AndroidTransportMediaDataSource(
        uri = "https://example.invalid/video.mkv",
        protocol = YSourceProtocol.Https,
        headers = emptyMap(),
        blockSizeOverride = 64,
        allowsSpeculativeWork = { false },
        createTransport = { BoundaryRangeTransport(media, requests, reportLength, disconnectAfter, truncateAfter) },
    )

    private fun onWorker(
        source: AndroidTransportMediaDataSource,
        verify: () -> Unit,
    ) {
        val worker = Executors.newSingleThreadExecutor()
        try {
            worker.submit(Callable { verify() }).get(20L, TimeUnit.SECONDS)
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }
}

/** Models a server that provides all Content-Range bytes but keeps the connection open. */
private class BoundaryRangeTransport(
    private val media: ByteArray,
    private val requests: MutableList<YMediaTransportRequest>,
    private val reportLength: Boolean,
    private val disconnectAfter: Int?,
    private val truncateAfter: Int?,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    private var attempt = 0
    private var position = 0
    private var start = 0
    private var end = 0
    private var truncateAt: Int? = null

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        requests.add(request)
        val range = checkNotNull(request.range)
        start = range.startInclusive.toInt()
        position = start
        end = minOf(checkNotNull(range.endInclusive) + 1L, media.size.toLong()).toInt()
        attempt++
        if (attempt == 1) truncateAt = truncateAfter?.let { start + it }
        check(start < end) { "Requested at or beyond EOF" }
        return YMediaTransportResponse(
            statusCode = 206,
            contentLength = media.size.toLong().takeIf { reportLength },
            acceptedRange = YByteRange(start.toLong(), end - 1L),
            entityTag = "\"version-a\"",
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position == end) throw IOException("Server kept the completed response open")
        if (truncateAt?.let { position >= it } == true) return -1
        if (attempt == 1 && disconnectAfter != null && position - start >= disconnectAfter) {
            throw IOException("Injected mid-response disconnection")
        }
        val availableEnd =
            minOf(
                end,
                truncateAt ?: end,
                disconnectAfter?.takeIf { attempt == 1 }?.let { start + it } ?: end,
            )
        val count = minOf(length, availableEnd - position)
        media.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() = Unit
}
