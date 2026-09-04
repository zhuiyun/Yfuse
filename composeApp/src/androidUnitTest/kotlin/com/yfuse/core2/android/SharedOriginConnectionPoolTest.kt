package com.yfuse.core2.android

import com.yfuse.core.network.sharedOriginConnectionPool
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Playback capability negotiation reaches the media server seconds before the first byte range is
 * asked for. While the API client and the media transport pooled connections separately, that
 * head start was discarded and the transport repeated the DNS, TCP and TLS work from scratch with
 * nothing on screen.
 */
class SharedOriginConnectionPoolTest {
    @Test
    fun a_media_byte_range_reuses_the_connection_the_api_client_already_opened() =
        runBlocking {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/4")
                    .setBody("data"),
            )
            server.start()
            try {
                // Stands in for the Emby API client: same pool, no TLS or proxy configuration, so
                // it describes the same OkHttp address as the media transport.
                val apiClient = OkHttpClient.Builder().connectionPool(sharedOriginConnectionPool).build()
                apiClient
                    .newCall(Request.Builder().url(server.url("/Items/1/PlaybackInfo")).build())
                    .execute()
                    // The connection only returns to the pool once its response body is drained.
                    .use { response -> response.body?.string() }

                val transport = AndroidHttpMediaTransport()
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("/Videos/1/stream").toString(),
                            protocol = YSourceProtocol.Http,
                            range = YByteRange(0L, 3L),
                        ),
                    )
                assertEquals(206, response.statusCode)
                transport.close()

                assertEquals(0, server.takeRequest().sequenceNumber)
                // Second request on the same connection: no new handshake was performed.
                assertEquals(1, server.takeRequest().sequenceNumber)
            } finally {
                server.shutdown()
            }
        }
}
