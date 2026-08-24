package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidAdaptiveHttpMediaTransportTest {
    @Test
    fun cronet_open_failure_falls_back_once_to_okhttp_transport() =
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
    fun cronet_body_failure_resumes_the_same_range_with_okhttp() =
        runBlocking {
            val cronet =
                FakeTransport(
                    reads =
                        listOf(
                            FakeRead.Bytes(byteArrayOf(1, 2, 3)),
                            FakeRead.Failure(IllegalStateException("stalled")),
                        ),
                )
            val fallback = FakeTransport(reads = listOf(FakeRead.Bytes(byteArrayOf(4, 5))))
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/movie.mp4",
                    protocol = YSourceProtocol.Https,
                    range = YByteRange(100L, 199L),
                )

            transport.open(request)
            val first = ByteArray(3)
            val second = ByteArray(2)

            assertEquals(3, transport.read(first, 0, first.size))
            assertEquals(2, transport.read(second, 0, second.size))

            assertContentEquals(byteArrayOf(1, 2, 3), first)
            assertContentEquals(byteArrayOf(4, 5), second)
            assertEquals(YByteRange(103L, 199L), fallback.openedRequests.single().range)
            assertTrue(cronet.closeCalls >= 1)

            transport.open(request)
            assertEquals(1, cronet.openCalls)
            assertEquals(2, fallback.openCalls)
        }

    @Test
    fun cancellation_during_cronet_read_is_not_converted_into_a_fallback() =
        runBlocking {
            val cronet =
                FakeTransport(
                    reads = listOf(FakeRead.Failure(CancellationException("cancelled"))),
                )
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
                    range = YByteRange(0L, 99L),
                )

            transport.open(request)
            var cancelled = false
            try {
                transport.read(ByteArray(1), 0, 1)
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertEquals(0, fallback.openCalls)
        }

    @Test
    fun a_partially_consumed_non_range_stream_is_never_replayed() =
        runBlocking {
            val failure = IllegalStateException("lost stream")
            val cronet =
                FakeTransport(
                    reads =
                        listOf(
                            FakeRead.Bytes(byteArrayOf(1)),
                            FakeRead.Failure(failure),
                        ),
                )
            val fallback = FakeTransport()
            val transport =
                AndroidAdaptiveHttpMediaTransport(
                    createCronet = { cronet },
                    createOkHttp = { fallback },
                )
            val request =
                YMediaTransportRequest(
                    uri = "https://media.example.test/live",
                    protocol = YSourceProtocol.Https,
                )

            transport.open(request)
            assertEquals(1, transport.read(ByteArray(1), 0, 1))
            var thrown: Throwable? = null
            try {
                transport.read(ByteArray(1), 0, 1)
            } catch (error: Throwable) {
                thrown = error
            }

            assertTrue(thrown === failure)
            assertEquals(0, fallback.openCalls)
        }
}

private sealed interface FakeRead {
    data class Bytes(
        val bytes: ByteArray,
    ) : FakeRead

    data class Failure(
        val error: Throwable,
    ) : FakeRead

    data object End : FakeRead
}

private class FakeTransport(
    private val failOpen: Boolean = false,
    private val reads: List<FakeRead> = emptyList(),
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    val openedRequests = mutableListOf<YMediaTransportRequest>()
    var openCalls = 0
    var closeCalls = 0
    private var readIndex = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        openCalls++
        openedRequests += request
        if (failOpen) error("unavailable")
        return YMediaTransportResponse(
            statusCode = 206,
            acceptedRange = request.range,
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        when (val read = reads.getOrNull(readIndex++) ?: FakeRead.End) {
            is FakeRead.Bytes -> {
                val count = minOf(length, read.bytes.size)
                read.bytes.copyInto(destination, offset, 0, count)
                count
            }
            is FakeRead.Failure -> throw read.error
            FakeRead.End -> -1
        }

    override suspend fun close() {
        closeCalls++
    }
}
