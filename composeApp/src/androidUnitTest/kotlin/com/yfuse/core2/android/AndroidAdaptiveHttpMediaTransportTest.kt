package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
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
}

private class FakeTransport(
    private val failOpen: Boolean = false,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http, YSourceProtocol.Https)
    override val features = setOf(YTransportFeature.ByteRange)
    var openCalls = 0
    var closeCalls = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        openCalls++
        if (failOpen) error("unavailable")
        return YMediaTransportResponse(statusCode = 206)
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int = -1

    override suspend fun close() {
        closeCalls++
    }
}
