package com.yfuse.watch

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CalendarStreamDeadlineTest {
    @Test
    fun headersThenStalledBodyIsClosedAndNextRequestCanComplete() {
        val releaseBody = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val readers = Executors.newSingleThreadExecutor()
        server.createContext("/stall") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.write('['.code)
                exchange.responseBody.flush()
                releaseBody.await(5, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.createContext("/next") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("[]".toByteArray()) }
        }
        server.start()
        try {
            val client = HttpClient.newHttpClient()
            val base = "http://127.0.0.1:${server.address.port}"
            val request = HttpRequest.newBuilder(URI("$base/stall")).timeout(Duration.ofSeconds(2)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val result =
                readers.submit<Throwable?> {
                    runCatching {
                        DeadlineInputStream(response.body(), timeoutMs = 100).use { it.readBytes() }
                    }.exceptionOrNull()
                }
            assertIs<SocketTimeoutException>(result.get(3, TimeUnit.SECONDS))
            releaseBody.countDown()
            val next = HttpRequest.newBuilder(URI("$base/next")).timeout(Duration.ofSeconds(2)).build()
            assertEquals("[]", client.send(next, HttpResponse.BodyHandlers.ofString()).body())
        } finally {
            releaseBody.countDown()
            server.stop(0)
            readers.shutdownNow()
        }
    }
}
