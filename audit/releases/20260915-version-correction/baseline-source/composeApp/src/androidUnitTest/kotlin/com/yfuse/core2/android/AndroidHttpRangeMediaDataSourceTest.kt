package com.yfuse.core2.android

import com.yfuse.core2.api.YPlaybackException
import com.yfuse.core2.api.YPlaybackFailureCategory
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AndroidHttpRangeMediaDataSourceTest {
    private val server = MockWebServer()

    @AfterTest
    fun closeServer() {
        server.close()
    }

    @Test
    fun `range source caches blocks and reads across boundaries`() {
        val media = ByteArray(160 * 1024) { index -> (index % 251).toByte() }
        server.dispatcher = mediaRangeDispatcher(media)
        server.start()
        val source =
            AndroidHttpRangeMediaDataSource(
                uri = server.url("/movie.mkv").toString(),
                headers = mapOf("User-Agent" to "Yfuse-Test"),
                blockSize = 64 * 1024,
                maximumCacheBytes = 128 * 1024,
            )

        assertEquals(media.size.toLong(), source.size)
        val first = ByteArray(1_024)
        assertEquals(first.size, source.readAt(1_000L, first, 0, first.size))
        assertContentEquals(media.copyOfRange(1_000, 2_024), first)
        assertEquals(1, server.requestCount)

        val crossing = ByteArray(1_024)
        val crossingStart = 64 * 1024 - 256
        assertEquals(crossing.size, source.readAt(crossingStart.toLong(), crossing, 0, crossing.size))
        assertContentEquals(media.copyOfRange(crossingStart, crossingStart + crossing.size), crossing)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `authorization response remains a typed non-device failure`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val source =
            AndroidHttpRangeMediaDataSource(
                uri = server.url("/private.mkv").toString(),
                headers = emptyMap(),
                blockSize = 64 * 1024,
                maximumCacheBytes = 64 * 1024,
            )

        val failure = assertFailsWith<YPlaybackException> { source.size }

        assertEquals(YPlaybackFailureCategory.Authorization, failure.category)
        assertFalse(failure.message.orEmpty().contains(server.hostName))
    }

    @Test
    fun `content range parser rejects shifted or impossible values`() {
        assertEquals(YHttpContentRange(0L, 9L, 100L), parseContentRange("bytes 0-9/100"))
        assertEquals(null, parseContentRange("bytes 9-0/100"))
        assertEquals(null, parseContentRange("bytes 0-100/100"))
        assertEquals(YHttpContentRange(0L, 9L, null), parseContentRange("bytes 0-9/*"))
    }
}

private fun mediaRangeDispatcher(media: ByteArray): Dispatcher =
    object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = requireNotNull(request.getHeader("Range"))
            val start = range.substringAfter("bytes=").substringBefore('-').toInt()
            val requestedEnd = range.substringAfter('-').toInt()
            val end = minOf(requestedEnd, media.lastIndex)
            val body = Buffer().write(media, start, end - start + 1)
            return MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $start-$end/${media.size}")
                .setBody(body)
        }
    }
