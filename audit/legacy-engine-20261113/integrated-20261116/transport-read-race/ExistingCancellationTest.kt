package com.yfuse.core2.android
import com.yfuse.core2.network.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.mockwebserver.*
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ExistingCancellationTest {
    @Test
    fun `normal range close preserves connection reuse for chunked bodies`() =
        runBlocking {
            val server = MockWebServer()
            val transport = AndroidHttpMediaTransport()
            repeat(2) {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-3/4")
                        .setChunkedBody("data", 2),
                )
            }
            server.start()
            try {
                repeat(2) { index ->
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("media").toString(),
                            protocol = YSourceProtocol.Http,
                            range = YByteRange(0L, 3L),
                        ),
                    )
                    val bytes = ByteArray(4)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = transport.read(bytes, offset, bytes.size - offset)
                        assertTrue(count > 0)
                        offset += count
                    }
                    transport.close()
                    assertContentEquals("data".encodeToByteArray(), bytes)
                    assertEquals(index, assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).sequenceNumber)
                }
            } finally {
                transport.close()
                server.shutdown()
            }
        }

    @Test
    fun `close cancels a request still waiting for headers and the transport can reopen`() =
        runBlocking {
            val server = MockWebServer()
            val worker = Executors.newSingleThreadExecutor()
            val transport = AndroidHttpMediaTransport()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/4")
                    .setBody("data"),
            )
            server.start()
            try {
                val request =
                    YMediaTransportRequest(
                        uri = server.url("media").toString(),
                        protocol = YSourceProtocol.Http,
                        range = YByteRange(0L, 3L),
                    )
                val opening =
                    worker.submit<Throwable?> {
                        runBlocking { runCatching { transport.open(request) }.exceptionOrNull() }
                    }
                assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))

                val cleanup =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            awaitCancellation()
                        } finally {
                            transport.close()
                        }
                    }
                cleanup.cancelAndJoin()
                val failure = opening.get(2, TimeUnit.SECONDS)
                assertTrue(failure is IOException || failure is CancellationException)

                assertEquals(206, transport.open(request).statusCode)
                val output = ByteArray(4)
                assertEquals(4, transport.read(output, 0, output.size))
                assertContentEquals("data".encodeToByteArray(), output)
            } finally {
                transport.close()
                worker.shutdownNow()
                server.shutdown()
            }
        }

    @Test
    fun `close also unblocks a stalled body read`() =
        runBlocking {
            val server = MockWebServer()
            val worker = Executors.newSingleThreadExecutor()
            val reading = CountDownLatch(1)
            val transport =
                AndroidHttpMediaTransport(
                    OkHttpClient
                        .Builder()
                        .addNetworkInterceptor { chain ->
                            val response = chain.proceed(chain.request())
                            val body = requireNotNull(response.body)
                            val observed =
                                object : ForwardingSource(body.source()) {
                                    override fun read(
                                        sink: Buffer,
                                        byteCount: Long,
                                    ): Long {
                                        // Observe entry into the actual body read. OkHttp's body-start
                                        // event may arrive only after bytes are received or the read fails.
                                        reading.countDown()
                                        return super.read(sink, byteCount)
                                    }
                                }.buffer()
                            response
                                .newBuilder()
                                .body(observed.asResponseBody(body.contentType(), body.contentLength()))
                                .build()
                        }.build(),
                )
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/4")
                    // Send headers but never deliver the declared body. A timed body delay
                    // outlives MockWebServer's shutdown deadline and hides the read assertion.
                    .setBody("")
                    .setHeader("Content-Length", "4"),
            )
            server.start()
            try {
                transport.open(
                    YMediaTransportRequest(
                        uri = server.url("media").toString(),
                        protocol = YSourceProtocol.Http,
                        range = YByteRange(0L, 3L),
                    ),
                )
                val pendingRead =
                    worker.submit<Result<Int>> {
                        runBlocking {
                            runCatching { transport.read(ByteArray(4), 0, 4) }
                        }
                    }
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                transport.close()
                val result = pendingRead.get(2, TimeUnit.SECONDS)
                assertTrue(result.isFailure || result.getOrThrow() == -1)
            } finally {
                transport.close()
                worker.shutdownNow()
                server.shutdown()
            }
        }

}
