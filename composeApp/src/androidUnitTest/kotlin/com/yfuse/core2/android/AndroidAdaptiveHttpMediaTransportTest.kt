package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFailureKind
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidAdaptiveHttpMediaTransportTest {
    @Test
    fun native_direct_okhttp_fallback_follows_same_origin_authenticated_redirects() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/media/movie.mkv"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/4")
                    .setBody("data"),
            )
            server.start()
            try {
                val transport =
                    AndroidAdaptiveHttpMediaTransport(
                        createCronet = { error("Cronet unavailable") },
                    )
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("redirect").toString(),
                            protocol = YSourceProtocol.Http,
                            range = YByteRange(0L, 3L),
                            headers = mapOf("X-Emby-Token" to "private"),
                        ),
                    )

                assertEquals(206, response.statusCode)
                assertEquals("private", server.takeRequest().getHeader("X-Emby-Token"))
                assertEquals("private", server.takeRequest().getHeader("X-Emby-Token"))
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun cronet_failure_falls_back_once_to_okhttp_transport() =
        runBlocking {
            val cronet = FakeTransport(failOpen = true)
            val fallback = FakeTransport()
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mkv",
                    protocol = YSourceProtocol.Https,
                )

            assertEquals(206, transport.open(request).statusCode)
            assertEquals(1, cronet.openCalls)
            assertEquals(1, fallback.openCalls)
            assertEquals(206, transport.open(request).statusCode)
            assertEquals(1, cronet.openCalls)
            assertEquals(2, fallback.openCalls)
            assertTrue(cronet.closeCalls >= 1)
        }

    @Test
    fun cronet_failure_is_shared_by_every_range_transport_for_the_same_media_source() =
        runBlocking {
            val routeState = AndroidAdaptiveHttpRouteState()
            val cronetCreations = AtomicInteger()
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mkv",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(0L, 3L),
                )

            repeat(2) {
                val transport =
                    AndroidAdaptiveHttpMediaTransport(
                        routeState = routeState,
                        createCronet = {
                            cronetCreations.incrementAndGet()
                            FakeTransport(failOpen = true)
                        },
                        createOkHttp = { FakeTransport() },
                    )
                assertEquals(206, transport.open(request).statusCode)
                transport.close()
            }

            assertEquals(1, cronetCreations.get())
        }

    @Test
    fun cronet_http11_route_is_used_once_then_shared_ranges_use_okhttp() =
        runBlocking {
            val routeState = AndroidAdaptiveHttpRouteState()
            val cronetCreations = AtomicInteger()
            val fallbackCreations = AtomicInteger()
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mkv",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(0L, 3L),
                )

            repeat(2) {
                val transport =
                    AndroidAdaptiveHttpMediaTransport(
                        routeState = routeState,
                        createCronet = {
                            cronetCreations.incrementAndGet()
                            FakeTransport(negotiatedProtocol = "http/1.1")
                        },
                        createOkHttp = {
                            fallbackCreations.incrementAndGet()
                            FakeTransport()
                        },
                    )
                assertEquals(206, transport.open(request).statusCode)
                transport.close()
            }

            assertEquals(1, cronetCreations.get())
            assertEquals(1, fallbackCreations.get())
        }

    @Test
    fun cronet_range_rejection_falls_back_before_media_extractor_sees_it() =
        runBlocking {
            val cronet = FakeTransport(statusCode = 200)
            val fallback = FakeTransport()
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mp4",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(0L, 3L),
                )

            assertEquals(206, transport.open(request).statusCode)
            assertEquals(1, cronet.openCalls)
            assertEquals(1, fallback.openCalls)
            assertTrue(cronet.closeCalls >= 1)
        }

    @Test
    fun cronet_mismatched_range_falls_back_before_binding() =
        runBlocking {
            val cronet = FakeTransport(acceptedRangeStartOffset = 1L)
            val fallback = FakeTransport()
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mp4",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(100L, 103L),
                )

            assertEquals(206, transport.open(request).statusCode)
            assertEquals(1, fallback.openCalls)
            assertTrue(cronet.closeCalls >= 1)
        }

    @Test
    fun cronet_read_failure_resumes_the_exact_remaining_range_with_okhttp() =
        runBlocking {
            val cronet =
                FakeTransport(
                    reads =
                        ArrayDeque(
                            listOf(
                                FakeRead.Bytes(byteArrayOf(1, 2)),
                                FakeRead.Failure(IllegalStateException("body timeout")),
                            ),
                        ),
                )
            val fallback =
                FakeTransport(
                    reads = ArrayDeque(listOf(FakeRead.Bytes(byteArrayOf(3, 4)))),
                )
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mp4",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(100L, 103L),
                )
            val bytes = ByteArray(4)

            transport.open(request)
            assertEquals(2, transport.read(bytes, 0, 2))
            assertEquals(2, transport.read(bytes, 2, 2))

            assertContentEquals(byteArrayOf(1, 2, 3, 4), bytes)
            assertEquals(YByteRange(102L, 103L), fallback.requests.single().range)
            assertTrue(cronet.closeCalls >= 1)

            transport.open(request.copy(range = YByteRange(200L, 203L)))
            assertEquals(1, cronet.openCalls, "Cronet stays disabled for the remaining session")
            assertEquals(2, fallback.openCalls)
        }

    @Test
    fun premature_cronet_eof_before_the_range_end_also_resumes_with_okhttp() =
        runBlocking {
            val cronet =
                FakeTransport(
                    reads =
                        ArrayDeque(
                            listOf(
                                FakeRead.Bytes(byteArrayOf(5, 6)),
                                FakeRead.End,
                            ),
                        ),
                )
            val fallback =
                FakeTransport(
                    reads = ArrayDeque(listOf(FakeRead.Bytes(byteArrayOf(7, 8)))),
                )
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mp4",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(0L, 3L),
                )
            val bytes = ByteArray(4)

            transport.open(request)
            assertEquals(2, transport.read(bytes, 0, 2))
            assertEquals(2, transport.read(bytes, 2, 2))

            assertContentEquals(byteArrayOf(5, 6, 7, 8), bytes)
            assertEquals(YByteRange(2L, 3L), fallback.requests.single().range)
        }

    @Test
    fun authorization_range_failure_keeps_the_status_for_non_retryable_diagnostics() =
        runBlocking {
            val cronet = FakeTransport(statusCode = 403)
            val fallback = FakeTransport(statusCode = 403)
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mkv",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(0L, 3L),
                )

            val failure = assertFailsWith<AndroidRangeResponseException> { transport.open(request) }

            assertEquals(YTransportFailureKind.Authorization, failure.failureKind)
            assertEquals(403, failure.statusCode)
            assertEquals(0L, failure.expectedRangeStart)
            assertEquals(1, cronet.openCalls)
            assertEquals(1, fallback.openCalls)
        }

    @Test
    fun later_403_refreshes_a_source_that_already_served_a_validated_range() =
        runBlocking {
            val retryTransports =
                ArrayDeque(
                    listOf(
                        FakeTransport(statusCode = 403),
                        FakeTransport(),
                    ),
                )
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { FakeTransport(negotiatedProtocol = "http/1.1") },
                    createOkHttp = { retryTransports.removeFirst() },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mkv",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(100L, 103L),
                )

            assertEquals(206, transport.open(request).statusCode)
            val staleRoute = assertFailsWith<AndroidRangeResponseException> { transport.open(request) }
            assertEquals(YTransportFailureKind.TransientIo, staleRoute.failureKind)
            assertEquals(403, staleRoute.statusCode)
            assertEquals(206, transport.open(request).statusCode)
        }

    @Test
    fun rejected_redirect_target_cannot_be_reintroduced_by_parallel_prefetch() {
        val state = AndroidHttpMediaRedirectState()
        val source = "https://media.example.test/items/1/stream"
        val redirected = "https://media.example.test/signed/movie.mkv"

        state.remember(source, redirected, stripCredentials = false)
        state.disableReuse(source)
        state.remember(source, redirected, stripCredentials = false)

        assertEquals(null, state.resolve(source))
    }
}

private sealed interface FakeRead {
    data class Bytes(
        val value: ByteArray,
    ) : FakeRead

    data class Failure(
        val error: Throwable,
    ) : FakeRead

    data object End : FakeRead
}

private class FakeTransport(
    private val failOpen: Boolean = false,
    private val reads: ArrayDeque<FakeRead> = ArrayDeque(),
    private val statusCode: Int = 206,
    private val acceptedRangeStartOffset: Long = 0L,
    private val negotiatedProtocol: String = "",
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    val requests = mutableListOf<YMediaTransportRequest>()
    var openCalls = 0
    var closeCalls = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        openCalls++
        requests += request
        if (failOpen) error("unavailable")
        val acceptedRange =
            request.range?.let { range ->
                YByteRange(
                    startInclusive = range.startInclusive + acceptedRangeStartOffset,
                    endInclusive = range.endInclusive,
                )
            }
        return YMediaTransportResponse(
            statusCode = statusCode,
            acceptedRange = acceptedRange,
            negotiatedProtocol = negotiatedProtocol,
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (reads.isEmpty()) return -1
        return when (val next = reads.removeFirst()) {
            is FakeRead.Bytes -> {
                val count = minOf(length, next.value.size)
                next.value.copyInto(destination, offset, 0, count)
                if (count < next.value.size) {
                    reads.addFirst(FakeRead.Bytes(next.value.copyOfRange(count, next.value.size)))
                }
                count
            }
            is FakeRead.Failure -> throw next.error
            FakeRead.End -> -1
        }
    }

    override suspend fun close() {
        closeCalls++
    }
}
