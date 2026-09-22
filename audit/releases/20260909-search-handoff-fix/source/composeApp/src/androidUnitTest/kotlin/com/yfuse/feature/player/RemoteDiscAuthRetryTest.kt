package com.yfuse.feature.player

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteDiscAuthRetryTest {
    @Test
    fun unauthorized_range_request_retries_once_with_fresh_headers() {
        val server = MockWebServer()
        val requests = AtomicInteger(0)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (requests.incrementAndGet()) {
                        1 -> MockResponse().setResponseCode(401)
                        else ->
                            MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Range", "bytes 0-2047/8192")
                                .setHeader("Content-Encoding", "identity")
                                .setBody("x".repeat(BLURAY_UDF_BLOCK_SIZE))
                    }
            }
        server.start()
        try {
            val headerRevision = AtomicInteger(0)
            val source =
                HttpRangeDiscBlockSource(
                    sourceUrl = server.url("/disc.iso").toString(),
                    headerProvider =
                        RemoteDiscHeaderProvider {
                            mapOf("X-Emby-Token" to "token-${headerRevision.incrementAndGet()}")
                        },
                )
            val target = ByteArray(BLURAY_UDF_BLOCK_SIZE)

            assertEquals(1, source.readBlocks(0, 1, target))
            assertEquals(2, requests.get())
            assertEquals("token-1", server.takeRequest().getHeader("X-Emby-Token"))
            assertEquals("token-2", server.takeRequest().getHeader("X-Emby-Token"))
            assertTrue(target.all { it == 'x'.code.toByte() })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun repeated_forbidden_response_is_terminal_and_never_loops() {
        val server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(403)
            }
        server.start()
        try {
            val headerCalls = AtomicInteger(0)
            val source =
                HttpRangeDiscBlockSource(
                    sourceUrl = server.url("/disc.iso").toString(),
                    headerProvider =
                        RemoteDiscHeaderProvider {
                            headerCalls.incrementAndGet()
                            mapOf("X-Emby-Token" to "still-invalid")
                        },
                )

            assertEquals(-1, source.readBlocks(0, 1, ByteArray(BLURAY_UDF_BLOCK_SIZE)))
            assertEquals(2, server.requestCount)
            assertEquals(2, headerCalls.get())
            assertTrue(source.lastFailure.orEmpty().contains("鉴权失败"))
        } finally {
            server.shutdown()
        }
    }
}
