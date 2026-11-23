package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackProxyRequest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlaybackCacheCallsTest {
    private val tag = "\"movie-1\""
    private val url = "https://media.invalid/movie"

    private fun response(
        request: Request,
        body: ResponseBody,
        code: Int = 206,
        range: String = "bytes 4-7/12",
        validator: String = tag,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("test")
            .header("Content-Range", range)
            .header("ETag", validator)
            .body(body)
            .build()

    private fun stalled(
        prefix: String,
        length: Long,
    ): ResponseBody {
        val pending = Buffer().writeUtf8(prefix)
        val source =
            object : Source {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long {
                    if (pending.size > 0) return pending.read(sink, byteCount)
                    sink.writeUtf8("?")
                    throw SocketTimeoutException("body timed out")
                }

                override fun timeout(): Timeout = Timeout.NONE

                override fun close() = Unit
            }
        return source.buffer().asResponseBody(null, length)
    }

    private fun exercise(
        firstTag: String = tag,
        cancelAfterPrefix: Boolean = false,
        success: Boolean = false,
        expectedRequests: Int = 2,
        next: (Request) -> Response,
    ) {
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    if (requests.size == 1) {
                        response(request, stalled("cd", 6), range = "bytes 2-7/12", validator = firstTag)
                    } else {
                        next(request)
                    }
                }.build()
        Socket().use { socket ->
            val owner = PlaybackProxyRequest(socket)
            val original =
                Request
                    .Builder()
                    .url(url)
                    .header("Range", "bytes=2-7")
                    .build()
            PlaybackCacheCalls(client).forRequest(owner).newCall(original).execute().use { received ->
                val body = received.body.source()
                assertEquals("cd", body.readUtf8(2))
                if (cancelAfterPrefix) owner.cancel()
                if (success) assertEquals("efgh", body.readUtf8()) else assertFailsWith<IOException> { body.readUtf8() }
            }
        }
        assertEquals(expectedRequests, requests.size)
        if (requests.size > 1) {
            assertEquals("bytes=4-7", requests[1].header("Range"))
            assertEquals(tag, requests[1].header("If-Range"))
        }
    }

    @Test
    fun timeoutDiscardsUnreturnedBytesAndReadsOnlyTheMissingSuffix() =
        exercise(success = true) { response(it, "efgh".toResponseBody()) }

    @Test
    fun changedRepresentationsAndMisalignedPartialResponsesAreRejected() {
        exercise { response(it, "efgh".toResponseBody(), validator = "\"movie-2\"") }
        exercise { response(it, "efgh".toResponseBody(), range = "bytes 4-7/13") }
        exercise { response(it, "efgh".toResponseBody(), range = "bytes 5-8/12") }
        exercise { response(it, "efgh".toResponseBody(), code = 200) }
        exercise { response(it, "efgh".toResponseBody()).newBuilder().header("Content-Encoding", "gzip").build() }
        exercise { response(it.newBuilder().url("https://other.invalid/movie").build(), "efgh".toResponseBody()) }
        exercise { response(it, "efg".toResponseBody()) }
    }

    @Test
    fun aSecondTimeoutIsReturnedAndAnUnvalidatedOrCancelledSourceIsNeverReopened() {
        exercise { response(it, stalled("", 4)) }
        exercise(firstTag = "W/\"weak\"", expectedRequests = 1) { error("weak source reopened") }
        exercise(cancelAfterPrefix = true, expectedRequests = 1) { error("cancelled source reopened") }
    }

    @Test
    fun aRealStalledHttpBodyResumesUnderTheOriginalValidator() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 2-7/12")
                    .setHeader("ETag", tag)
                    .setBody("cdefgh")
                    .throttleBody(2, 2, TimeUnit.SECONDS),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(
                        206,
                    ).setHeader("Content-Range", "bytes 4-7/12")
                    .setHeader("ETag", tag)
                    .setBody("efgh"),
            )
            val client = OkHttpClient.Builder().readTimeout(350, TimeUnit.MILLISECONDS).build()
            Socket().use { socket ->
                val request =
                    Request
                        .Builder()
                        .url(server.url("/movie"))
                        .header("Range", "bytes=2-7")
                        .build()
                PlaybackCacheCalls(client).forRequest(PlaybackProxyRequest(socket)).newCall(request).execute().use {
                    assertEquals("cdefgh", it.body.string())
                }
            }
            assertEquals("bytes=2-7", server.takeRequest().getHeader("Range"))
            val resumed = server.takeRequest()
            assertEquals("bytes=4-7", resumed.getHeader("Range"))
            assertEquals(tag, resumed.getHeader("If-Range"))
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun wholeFileResponseResumesFromItsActualDeliveredPrefix() {
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    if (requests.size == 1) {
                        response(request, stalled("ab", 6), code = 200)
                            .newBuilder()
                            .removeHeader("Content-Range")
                            .header("Accept-Ranges", "bytes")
                            .build()
                    } else {
                        response(request, "cdef".toResponseBody(), range = "bytes 2-5/6")
                    }
                }.build()
        Socket().use { socket ->
            val request = Request.Builder().url(url).build()
            PlaybackCacheCalls(client).forRequest(PlaybackProxyRequest(socket)).newCall(request).execute().use {
                assertEquals("abcdef", it.body.string())
            }
        }
        assertEquals(listOf(null, "bytes=2-5"), requests.map { it.header("Range") })
    }

    @Test
    fun aResumeRedirectIsRejectedBeforeARequestReachesTheNewOrigin() {
        MockWebServer().use { original ->
            MockWebServer().use { other ->
                original.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 2-7/12")
                        .setHeader("ETag", tag)
                        .setBody("cdefgh")
                        .throttleBody(2, 2, TimeUnit.SECONDS),
                )
                original.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/different")))
                other.enqueue(MockResponse().setBody("wrong"))
                val client = OkHttpClient.Builder().readTimeout(350, TimeUnit.MILLISECONDS).build()
                Socket().use { socket ->
                    val request =
                        Request
                            .Builder()
                            .url(original.url("/movie"))
                            .header("Range", "bytes=2-7")
                            .build()
                    PlaybackCacheCalls(client).forRequest(PlaybackProxyRequest(socket)).newCall(request).execute().use {
                        assertFailsWith<IOException> { it.body.string() }
                    }
                }
                assertEquals(2, original.requestCount)
                assertEquals(0, other.requestCount)
            }
        }
    }
}
