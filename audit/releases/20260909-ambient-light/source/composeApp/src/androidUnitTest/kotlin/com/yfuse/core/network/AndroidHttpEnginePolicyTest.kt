package com.yfuse.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpEngine
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AndroidHttpEnginePolicyTest {
    @Test
    fun emby_dispatcher_bounds_startup_connection_fanout() {
        val dispatcher = embyRequestDispatcher()

        assertEquals(EMBY_MAX_CONCURRENT_REQUESTS, dispatcher.maxRequests)
        assertEquals(EMBY_MAX_CONCURRENT_REQUESTS_PER_HOST, dispatcher.maxRequestsPerHost)
        assertTrue(dispatcher.maxRequestsPerHost < dispatcher.maxRequests)
    }

    @Test
    fun android_actual_uses_the_websocket_capable_okhttp_engine() {
        val engine = embyHttpEngine()

        try {
            assertIs<OkHttpEngine>(engine)
            assertTrue(WebSocketCapability in engine.supportedCapabilities)
        } finally {
            engine.close()
        }
    }

    @Test
    fun android_actual_rejects_an_untrusted_https_certificate() =
        runBlocking {
            val heldCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("localhost")
                    .build()
            val serverCertificates =
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(heldCertificate)
                    .build()
            val server =
                MockWebServer().apply {
                    useHttps(serverCertificates.sslSocketFactory(), false)
                    enqueue(MockResponse().setBody("must not be trusted"))
                    start()
                }
            val client = HttpClient(embyHttpEngine())

            try {
                val failure =
                    assertFails {
                        client.get(server.url("/private").toString()).bodyAsText()
                    }
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any { it is SSLHandshakeException },
                    "Expected a certificate handshake failure, got $failure",
                )
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun okhttp_rejects_a_trusted_certificate_for_the_wrong_hostname() =
        runBlocking {
            val heldCertificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("expected.example")
                    .build()
            val serverCertificates =
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(heldCertificate)
                    .build()
            val clientCertificates =
                HandshakeCertificates
                    .Builder()
                    .addTrustedCertificate(heldCertificate.certificate)
                    .build()
            val server =
                MockWebServer().apply {
                    useHttps(serverCertificates.sslSocketFactory(), false)
                    enqueue(MockResponse().setBody("wrong host must be rejected"))
                    start()
                }
            val client =
                HttpClient(
                    OkHttp.create {
                        config {
                            sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                        }
                    },
                )

            try {
                val failure =
                    assertFails {
                        client.get(server.url("/wrong-host").toString()).bodyAsText()
                    }
                assertTrue(
                    generateSequence(failure as Throwable?) { it.cause }
                        .any { it is SSLPeerUnverifiedException },
                    "Expected a hostname verification failure, got $failure",
                )
            } finally {
                client.close()
                server.shutdown()
            }
        }

    @Test
    fun android_actual_completes_a_real_websocket_handshake() =
        runBlocking {
            val server =
                MockWebServer().apply {
                    enqueue(
                        MockResponse().withWebSocketUpgrade(
                            object : WebSocketListener() {
                                override fun onMessage(
                                    webSocket: WebSocket,
                                    text: String,
                                ) {
                                    webSocket.send("echo:$text")
                                    webSocket.close(1000, "test complete")
                                }
                            },
                        ),
                    )
                    start()
                }
            val client = HttpClient(embyHttpEngine()) { install(WebSockets) }
            val url = server.url("/echo").toString().replaceFirst("http://", "ws://")

            try {
                withTimeout(5_000L) {
                    client.webSocket(urlString = url) {
                        send(Frame.Text("ping"))
                        assertEquals("echo:ping", (incoming.receive() as Frame.Text).readText())
                    }
                }
            } finally {
                client.close()
                server.shutdown()
            }
        }
}
