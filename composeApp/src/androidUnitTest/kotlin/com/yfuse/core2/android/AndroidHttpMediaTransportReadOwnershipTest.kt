package com.yfuse.core2.android

import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidHttpMediaTransportReadOwnershipTest {
    @Test
    fun `cancelling a range never closes its buffered source while a read owns it`() = exerciseCloseDuringRead(false)

    @Test
    fun `a cancelled reader releases only its old response after another range opens`() = exerciseCloseDuringRead(true)

    private fun exerciseCloseDuringRead(reopen: Boolean) =
        runBlocking {
            val reading = CountDownLatch(1)
            val releaseRead = CountDownLatch(1)
            val oldCloses = AtomicInteger()
            val newCloses = AtomicInteger()
            val requests = AtomicInteger()
            val worker = Executors.newSingleThreadExecutor()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val old = requests.getAndIncrement() == 0
                        val body =
                            object : ForwardingSource(Buffer().writeUtf8(if (old) "old!" else "new!")) {
                                override fun read(
                                    sink: Buffer,
                                    byteCount: Long,
                                ): Long {
                                    if (old) {
                                        reading.countDown()
                                        check(releaseRead.await(5, TimeUnit.SECONDS))
                                    }
                                    return super.read(sink, byteCount)
                                }

                                override fun close() {
                                    (if (old) oldCloses else newCloses).incrementAndGet()
                                    super.close()
                                }
                            }.buffer().asResponseBody(null, 4)
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body)
                            .build()
                    }.build()
            val transport = AndroidHttpMediaTransport(client)
            val request = YMediaTransportRequest("http://media.invalid/video", YSourceProtocol.Http)
            try {
                transport.open(request)
                val pending = worker.submit { runBlocking { runCatching { transport.read(ByteArray(4), 0, 4) } } }
                assertTrue(reading.await(2, TimeUnit.SECONDS))
                transport.close()
                assertEquals(0, oldCloses.get(), "Closing must not mutate the buffer still owned by the reader")
                if (reopen) {
                    transport.open(request)
                    val bytes = ByteArray(4)
                    assertEquals(4, transport.read(bytes, 0, bytes.size))
                    assertContentEquals("new!".encodeToByteArray(), bytes)
                }
                releaseRead.countDown()
                pending.get(2, TimeUnit.SECONDS)
                assertEquals(1, oldCloses.get(), "The exiting reader must release its cancelled response")
                assertEquals(0, newCloses.get(), "An old reader must not close the replacement response")
                transport.close()
                assertEquals(if (reopen) 1 else 0, newCloses.get())
            } finally {
                releaseRead.countDown()
                transport.close()
                worker.shutdownNow()
                worker.awaitTermination(2, TimeUnit.SECONDS)
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
}
