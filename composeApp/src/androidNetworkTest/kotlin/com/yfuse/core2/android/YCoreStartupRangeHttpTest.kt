package com.yfuse.core2.android

import com.yfuse.core2.network.YSourceProtocol
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class YCoreStartupRangeHttpTest {
    @Test
    fun continuousDemuxReadsUseASingleHttpRangePerFollowingBlockWithoutAnEntityTag() {
        val blockSize = 2 * 1024 * 1024
        val media = ByteArray(3 * blockSize) { (it % 251).toByte() }
        val ranges = CopyOnWriteArrayList<String>()
        val client = OkHttpClient()
        MockWebServer().use { server ->
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val range = requireNotNull(request.getHeader("Range"))
                        val bounds = range.removePrefix("bytes=").split('-').map(String::toInt)
                        val first = bounds.first()
                        val last = minOf(bounds.last(), media.lastIndex)
                        ranges.add(range)
                        return MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range", "bytes $first-$last/${media.size}")
                            .setBody(Buffer().write(media, first, last - first + 1))
                            .setHeadersDelay(80, TimeUnit.MILLISECONDS)
                            .throttleBody(64 * 1024, 5, TimeUnit.MILLISECONDS)
                    }
                }
            AndroidTransportMediaDataSource(
                uri = server.url("/movie").toString(),
                protocol = YSourceProtocol.Http,
                headers = emptyMap(),
                createTransport = { AndroidHttpMediaTransport(client) },
                prioritizeStartupReads = true,
                memoryLeaseOverride =
                    PlaybackMemoryPool(64L * 1024 * 1024).acquire(
                        PlaybackBufferKind.Transport,
                        64L * 1024 * 1024,
                    ),
                allowsSpeculativeWork = { true },
                refreshMemoryPressure = {},
            ).use { source ->
                val chunk = ByteArray(32 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                val length = 2 * blockSize + chunk.size
                for (position in 0 until length step chunk.size) {
                    assertEquals(chunk.size, source.readAt(position.toLong(), chunk, 0, chunk.size))
                    digest.update(chunk)
                }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(media.copyOf(length)), digest.digest())
                for (first in listOf(blockSize, 2 * blockSize)) {
                    assertEquals(
                        "Sequential demux must use one HTTP exchange for the next block",
                        listOf("bytes=$first-${first + blockSize - 1}"),
                        ranges.filter { it.startsWith("bytes=$first-") },
                    )
                }
            }
        }
    }
}
