package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedRouteHttpMediaTransportTest {
    @Test
    fun separate_opens_of_one_media_uri_reuse_its_resolved_redirect_without_credentials() =
        runBlocking {
            val origin = MockWebServer()
            val cdn = MockWebServer()
            cdn.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/8")
                    .setBody("0123"),
            )
            cdn.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/8")
                    .setBody("4567"),
            )
            cdn.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("movie.mkv")))
            origin.start()
            try {
                val sourceUri = origin.url("redirect").toString()
                // The next-item warm-up and the FFmpeg proxy each build their own transport.
                listOf(YByteRange(0L, 3L), YByteRange(4L, 7L)).forEach { range ->
                    val transport = sharedRouteHttpMediaTransport(sourceUri)
                    try {
                        val response =
                            transport.open(
                                YMediaTransportRequest(
                                    uri = sourceUri,
                                    protocol = YSourceProtocol.Http,
                                    headers = mapOf("X-Emby-Token" to "private"),
                                    range = range,
                                ),
                            )
                        assertEquals(206, response.statusCode)
                    } finally {
                        transport.close()
                    }
                }

                assertEquals(1, origin.requestCount)
                assertEquals(2, cdn.requestCount)
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                repeat(2) { assertNull(cdn.takeRequest().getHeader("X-Emby-Token")) }
            } finally {
                origin.shutdown()
                cdn.shutdown()
            }
        }
}
