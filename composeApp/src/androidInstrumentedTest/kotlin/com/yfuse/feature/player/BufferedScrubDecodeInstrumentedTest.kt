package com.yfuse.feature.player

import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yfuse.core.playback.VideoCachePool
import com.yfuse.core.playback.secureMediaCacheKeyForUrl
import com.yfuse.core2.android.BufferedScrubMedia
import com.yfuse.core2.api.YBufferRangeCoverage
import com.yfuse.core2.api.YBufferSnapshot
import com.yfuse.core2.api.YBufferedRange
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class BufferedScrubDecodeInstrumentedTest {
    @Test
    fun refusedSourceReadReopensAtSameOffsetAndCommitsOnlyReturnedBytes() = verifyCacheRead(false)

    @Test
    fun bodyTimeoutResumesAtTheFirstMissingByteAndCachesOnlyVerifiedBytes() = verifyCacheRead(true)

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun verifyCacheRead(stallBody: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val url = "http://127.0.0.1:1/${UUID.randomUUID()}/episode.mp4"
        val key = secureMediaCacheKeyForUrl(url)
        val limit = 8L * 1024 * 1024
        val ranges = CopyOnWriteArrayList<String>()
        val expected = byteArrayOf(31, 41, 59, 26)
        val client =
            PlaybackHttpDataSource.client
                .newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    ranges.add(request.header("Range").orEmpty())
                    val first = ranges.size == 1
                    if (first && !stallBody) throw IOException("stream was reset: REFUSED_STREAM")
                    val body =
                        when {
                            first -> {
                                val prefix = Buffer().write(expected, 0, 2)
                                object : ForwardingSource(prefix) {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        if (prefix.exhausted()) throw SocketTimeoutException("fixture body timeout")
                                        return super.read(sink, byteCount)
                                    }
                                }.buffer().asResponseBody(null, 4L)
                            }
                            stallBody -> expected.copyOfRange(2, 4).toResponseBody()
                            else -> expected.toResponseBody()
                        }
                    val start = if (stallBody && !first) 5 else 3
                    if (stallBody && !first) assertEquals("\"test-episode\"", request.header("If-Range"))
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_2)
                        .code(206)
                        .message("Partial Content")
                        .header("ETag", "\"test-episode\"")
                        .header("Content-Range", "bytes $start-6/16")
                        .header("Content-Length", body.contentLength().toString())
                        .body(body)
                        .build()
                }.build()
        AndroidPlaybackHttpProxy(context, "cache-range-test", limit, upstreamClient = client).use { proxy ->
            val local = URL(proxy.localUrl(url, cacheable = true))

            fun readRange(): ByteArray {
                val connection = local.openConnection() as HttpURLConnection
                return try {
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("Range", "bytes=3-6")
                    assertEquals(206, connection.responseCode)
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
            }
            requireNotNull(VideoCachePool.acquire(context, limit)).use { handle ->
                try {
                    val firstRead = runCatching { readRange() }
                    if (stallBody) {
                        assertEquals(
                            "A body timeout must reopen the missing suffix",
                            listOf("bytes=3-6", "bytes=5-6"),
                            ranges.toList(),
                        )
                    }
                    assertArrayEquals(expected, firstRead.getOrThrow())
                    val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
                    while (!handle.cache.isCached(key, 3L, 4L) && android.os.SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(20)
                    }
                    assertTrue(handle.cache.isCached(key, 3L, 4L))
                    assertFalse(handle.cache.isCached(key, 2L, 1L))
                    assertFalse(handle.cache.isCached(key, 7L, 1L))
                    assertArrayEquals(expected, readRange())
                    val secondRange = if (stallBody) "bytes=5-6" else "bytes=3-6"
                    assertEquals(listOf("bytes=3-6", secondRange), ranges.toList())
                } finally {
                    handle.cache.removeResource(key)
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun latePreviewReadsOnlyCommittedSenPlayerCacheSpans() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val limit = 8L * 1024 * 1024
        val url = "http://127.0.0.1:1/${java.util.UUID.randomUUID()}/movie.mp4"
        val key = secureMediaCacheKeyForUrl(url)
        AndroidPlaybackHttpProxy(context, "", limit).use { proxy ->
            proxy.localUrl(url, cacheable = true)
            requireNotNull(VideoCachePool.acquire(context, limit)).use { handle ->
                val cache = handle.cache
                try {
                    val bytes = byteArrayOf(7, 23, 39, 51)
                    val hole = cache.startReadWrite(key, 0, bytes.size.toLong())
                    try {
                        val file = cache.startFile(key, 0, bytes.size.toLong())
                        file.writeBytes(bytes)
                        cache.commitFile(file, file.length())
                    } finally {
                        cache.releaseHoleSpan(hole)
                    }
                    cache.applyContentMetadataMutations(
                        key,
                        ContentMetadataMutations.setContentLength(ContentMetadataMutations(), 16L),
                    )
                    BufferedScrubMedia.attach(url, emptyMap(), null).use { session ->
                        val input = requireNotNull(session.input(0)).first
                        input.use {
                            val result = ByteArray(bytes.size)
                            assertEquals(bytes.size, it.readAt(0, result, 0, result.size))
                            assertArrayEquals(bytes, result)
                            assertThrows(java.io.EOFException::class.java) { it.readAt(8, result, 0, result.size) }
                            assertFalse(it.valid())
                        }
                    }
                } finally {
                    cache.removeResource(key)
                }
            }
        }
    }

    @Test fun decodeUsesPlaybackBytesAndRejectsMissingDataAndOldEpisode() {
        val bytes =
            InstrumentationRegistry.getInstrumentation().context.assets.open("ycore/scrub-avc.mp4.b64").use {
                android.util.Base64.decode(it.readBytes(), android.util.Base64.DEFAULT)
            }
        // No host or server is bound to this address. Only the already buffered data can supply a picture.
        val url = "http://127.0.0.1:1/fixture.mp4"
        val s = BufferedScrubMedia.attach(url, emptyMap(), null)
        try {
            val writer = requireNotNull(BufferedScrubMedia.lookup(url, emptyMap(), null))
            assertNull(s.input(1_000_000))
            writer.put(0, bytes, 0, 64, bytes.size.toLong())
            val partial = s.input(2_000_000)!!.first
            assertTrue(runCatching { partial.readAt(128, ByteArray(32), 0, 32) }.isFailure)
            assertFalse(partial.valid())
            partial.close()
            writer.put(0, bytes, 0, bytes.size, bytes.size.toLong())
            val a = requireNotNull(decodeCachedScrubFrame(s, 1_000_000))
            val b = requireNotNull(decodeCachedScrubFrame(s, 7_000_000))
            try {
                assertFalse(a.sameAs(b))
            } finally {
                a.recycle()
                b.recycle()
            }
            val ranges =
                YBufferSnapshot(
                    coverage = YBufferRangeCoverage.SeekableRanges,
                    ranges = listOf(YBufferedRange(0, 2000), YBufferedRange(6000, 8000)),
                )
            assertTrue(ranges.hasScrubPosition(1999))
            assertFalse(ranges.hasScrubPosition(2000))
            assertFalse(ranges.hasScrubPosition(5999))
            assertTrue(ranges.hasScrubPosition(6000))
            val next = BufferedScrubMedia.attach("http://127.0.0.1:1/ep2.mp4", emptyMap(), null)
            try {
                writer.put(0, bytes, 0, bytes.size, bytes.size.toLong())
                assertNull(s.input(1_000_000))
                assertNull(next.input(1_000_000))
            } finally {
                next.close()
            }
        } finally {
            s.close()
        }
    }
}
