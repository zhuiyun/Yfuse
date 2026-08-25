package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidAdaptiveHttpMediaTransportTest {
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
    fun cronet_body_failure_resumes_the_remaining_range_with_okhttp() =
        runBlocking {
            val cronet =
                FakeTransport(
                    readResults =
                        listOf(
                            FakeRead.Data(byteArrayOf(1, 2, 3, 4)),
                            FakeRead.Failure,
                        ),
                )
            val fallback =
                FakeTransport(
                    readResults = listOf(FakeRead.Data(byteArrayOf(5, 6))),
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
                    range = YByteRange(100L, 109L),
                )

            assertEquals(206, transport.open(request).statusCode)
            val first = ByteArray(4)
            assertEquals(4, transport.read(first, 0, first.size))
            assertContentEquals(byteArrayOf(1, 2, 3, 4), first)

            val second = ByteArray(2)
            assertEquals(2, transport.read(second, 0, second.size))
            assertContentEquals(byteArrayOf(5, 6), second)
            assertEquals(YByteRange(104L, 109L), fallback.openedRequests.single().range)
            assertEquals(1, fallback.openCalls)
            assertTrue(cronet.closeCalls >= 1)
        }
}

private sealed interface FakeRead {
    data class Data(
        val bytes: ByteArray,
    ) : FakeRead

    data object End : FakeRead

    data object Failure : FakeRead
}

private class FakeTransport(
    private val failOpen: Boolean = false,
    readResults: List<FakeRead> = listOf(FakeRead.End),
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    private val reads = readResults.toMutableList()
    val openedRequests = mutableListOf<YMediaTransportRequest>()
    var openCalls = 0
    var closeCalls = 0

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
        when (val result = if (reads.isEmpty()) FakeRead.End else reads.removeAt(0)) {
            is FakeRead.Data -> {
                val count = minOf(length, result.bytes.size)
                result.bytes.copyInto(destination, offset, 0, count)
                count
            }
            FakeRead.End -> -1
            FakeRead.Failure -> error("stream failed")
        }

    override suspend fun close() {
        closeCalls++
    }
}
