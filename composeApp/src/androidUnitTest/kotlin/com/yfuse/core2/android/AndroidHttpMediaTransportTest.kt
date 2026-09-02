package com.yfuse.core2.android

import com.yfuse.core2.demux.YDemuxSource
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AndroidHttpMediaTransportTest {
    @Test
    fun `FFmpeg enhanced WebDAV source keeps authorization after scheme normalization`() {
        val request =
            ffmpegSourceRequest(
                YDemuxSource(
                    uri = "webdavs://media.example/library/movie.mkv",
                    headers = mapOf("X-Media-Key" to "opaque"),
                    transportCredentials = YTransportCredentials.UsernamePassword("alice", "secret"),
                ),
            )

        assertEquals("https://media.example/library/movie.mkv", request.uri)
        assertEquals("opaque", request.headers["X-Media-Key"])
        assertEquals("Basic YWxpY2U6c2VjcmV0", request.headers["Authorization"])
    }

    @Test
    fun `FFmpeg enhanced source never replaces explicit authorization`() {
        val request =
            ffmpegSourceRequest(
                YDemuxSource(
                    uri = "https://media.example/movie.mkv",
                    headers = mapOf("authorization" to "Bearer signed"),
                    transportCredentials = YTransportCredentials.UsernamePassword("alice", "secret"),
                ),
            )

        assertEquals(mapOf("authorization" to "Bearer signed"), request.headers)
    }

    @Test
    fun `range 416 exposes total length for exact EOF handling`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(416)
                    .setHeader("Content-Range", "bytes */8192"),
            )
            server.start()
            try {
                val transport = AndroidHttpMediaTransport()
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("movie.iso").toString(),
                            protocol = YSourceProtocol.Http,
                            range = YByteRange(8192, 10239),
                        ),
                    )
                assertEquals(416, response.statusCode)
                assertEquals(8192L, response.contentLength)
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `WebDAV credentials become basic authorization without overriding an explicit header`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(200).setBody("media"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("media"))
            server.start()
            try {
                val transport = AndroidHttpMediaTransport()
                val credentials = YTransportCredentials.UsernamePassword("alice", "secret")

                transport.open(
                    YMediaTransportRequest(
                        uri = server.url("dav/movie.mkv").toString(),
                        protocol = YSourceProtocol.WebDav,
                        credentials = credentials,
                    ),
                )
                assertEquals("Basic YWxpY2U6c2VjcmV0", server.takeRequest().getHeader("Authorization"))
                transport.open(
                    YMediaTransportRequest(
                        uri = server.url("dav/movie.mkv").toString(),
                        protocol = YSourceProtocol.WebDav,
                        headers = mapOf("Authorization" to "Bearer provider-token"),
                        credentials = credentials,
                    ),
                )
                assertEquals("Bearer provider-token", server.takeRequest().getHeader("Authorization"))
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `opens strict byte range and reads response incrementally`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/10")
                    .setBody("4567"),
            )
            server.start()
            try {
                val transport = AndroidHttpMediaTransport(OkHttpClient())
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("media").toString(),
                            protocol = YSourceProtocol.Http,
                            range = YByteRange(4, 7),
                        ),
                    )
                val output = ByteArray(4)
                assertEquals(4, transport.read(output, 0, output.size))
                assertContentEquals("4567".encodeToByteArray(), output)
                assertEquals(206, response.statusCode)
                assertEquals(YByteRange(4, 7), response.acceptedRange)
                assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `WebDAV uses the same range safe transport without following redirects`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid"))
            server.start()
            try {
                val transport = AndroidHttpMediaTransport(OkHttpClient.Builder().followRedirects(false).build())
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("dav/movie.mkv").toString(),
                            protocol = YSourceProtocol.WebDav,
                        ),
                    )
                assertEquals(302, response.statusCode)
                assertEquals(1, server.requestCount)
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `YCore proxy transport follows media redirects and preserves byte ranges`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/cdn/movie.mkv"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/10")
                    .setBody("4567"),
            )
            server.start()
            try {
                val transport =
                    AndroidHttpMediaTransport(
                        client = OkHttpClient.Builder().followRedirects(false).build(),
                        followSafeRedirects = true,
                    )
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = server.url("redirect/movie.mkv").toString(),
                            protocol = YSourceProtocol.Http,
                            headers = mapOf("User-Agent" to "Yfuse-test"),
                            range = YByteRange(4, 7),
                        ),
                    )

                assertEquals(206, response.statusCode)
                assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
                assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `redirectable media transport keeps provider credentials on the same origin`() =
        runTest {
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/media/movie.mkv"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("media"))
            server.start()
            try {
                val transport = AndroidHttpMediaTransport(followSafeRedirects = true)

                assertEquals(
                    200,
                    transport
                        .open(
                            YMediaTransportRequest(
                                uri = server.url("redirect").toString(),
                                protocol = YSourceProtocol.Http,
                                headers = mapOf("X-Emby-Token" to "private"),
                            ),
                        ).statusCode,
                )
                assertEquals("private", server.takeRequest().getHeader("X-Emby-Token"))
                assertEquals("private", server.takeRequest().getHeader("X-Emby-Token"))
                transport.close()
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `redirectable media transport strips provider credentials across origins`() =
        runTest {
            val origin = MockWebServer()
            val cdn = MockWebServer()
            cdn.enqueue(MockResponse().setResponseCode(200).setBody("media"))
            cdn.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("movie.mkv")))
            origin.start()
            try {
                val transport = AndroidHttpMediaTransport(followSafeRedirects = true)

                assertEquals(
                    200,
                    transport
                        .open(
                            YMediaTransportRequest(
                                uri = origin.url("redirect").toString(),
                                protocol = YSourceProtocol.Http,
                                headers =
                                    mapOf(
                                        "X-Emby-Token" to "private",
                                        "User-Agent" to "Yfuse-test",
                                    ),
                            ),
                        ).statusCode,
                )
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                val redirected = cdn.takeRequest()
                assertEquals(null, redirected.getHeader("X-Emby-Token"))
                assertEquals("Yfuse-test", redirected.getHeader("User-Agent"))
                transport.close()
            } finally {
                origin.shutdown()
                cdn.shutdown()
            }
        }

    @Test
    fun `resolved cross origin media route is reused by later byte ranges`() =
        runTest {
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
                val redirectState = AndroidHttpMediaRedirectState()
                val sourceUri = origin.url("redirect").toString()
                listOf(YByteRange(0L, 3L), YByteRange(4L, 7L)).forEach { range ->
                    val transport =
                        AndroidHttpMediaTransport(
                            followSafeRedirects = true,
                            redirectState = redirectState,
                        )
                    assertEquals(
                        206,
                        transport
                            .open(
                                YMediaTransportRequest(
                                    uri = sourceUri,
                                    protocol = YSourceProtocol.Http,
                                    headers =
                                        mapOf(
                                            "X-Emby-Token" to "private",
                                            "User-Agent" to "Yfuse-test",
                                        ),
                                    range = range,
                                ),
                            ).statusCode,
                    )
                    transport.close()
                }

                assertEquals(1, origin.requestCount)
                assertEquals(2, cdn.requestCount)
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                assertEquals(null, cdn.takeRequest().getHeader("X-Emby-Token"))
                assertEquals(null, cdn.takeRequest().getHeader("X-Emby-Token"))
            } finally {
                origin.shutdown()
                cdn.shutdown()
            }
        }

    @Test
    fun `expired cached media route refreshes through the authenticated origin`() =
        runTest {
            val origin = MockWebServer()
            val cdn = MockWebServer()
            cdn.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 0-3/8")
                    .setBody("0123"),
            )
            cdn.enqueue(MockResponse().setResponseCode(403))
            cdn.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/8")
                    .setBody("4567"),
            )
            cdn.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("old.mkv")))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("new.mkv")))
            origin.start()
            try {
                val redirectState = AndroidHttpMediaRedirectState()
                val sourceUri = origin.url("redirect").toString()
                listOf(YByteRange(0L, 3L), YByteRange(4L, 7L)).forEach { range ->
                    val transport =
                        AndroidHttpMediaTransport(
                            followSafeRedirects = true,
                            redirectState = redirectState,
                        )
                    assertEquals(
                        206,
                        transport
                            .open(
                                YMediaTransportRequest(
                                    uri = sourceUri,
                                    protocol = YSourceProtocol.Http,
                                    headers = mapOf("X-Emby-Token" to "private"),
                                    range = range,
                                ),
                            ).statusCode,
                    )
                    transport.close()
                }

                assertEquals(2, origin.requestCount)
                assertEquals(3, cdn.requestCount)
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                repeat(3) { assertEquals(null, cdn.takeRequest().getHeader("X-Emby-Token")) }
            } finally {
                origin.shutdown()
                cdn.shutdown()
            }
        }

    @Test
    fun `native media transport follows secure to cleartext redirect without leaking credentials`() =
        runTest {
            val certificate =
                HeldCertificate
                    .Builder()
                    .addSubjectAlternativeName("localhost")
                    .addSubjectAlternativeName("127.0.0.1")
                    .build()
            val serverCertificates =
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(certificate)
                    .build()
            val clientCertificates =
                HandshakeCertificates
                    .Builder()
                    .addTrustedCertificate(certificate.certificate)
                    .build()
            val origin = MockWebServer().apply { useHttps(serverCertificates.sslSocketFactory(), false) }
            val media = MockWebServer()
            media.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes 4-7/10")
                    .setBody("4567"),
            )
            media.start()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", media.url("movie.mkv")))
            origin.start()
            try {
                val client =
                    OkHttpClient
                        .Builder()
                        .sslSocketFactory(
                            clientCertificates.sslSocketFactory(),
                            clientCertificates.trustManager,
                        )
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build()
                val transport =
                    AndroidHttpMediaTransport(
                        client = client,
                        followSafeRedirects = true,
                        allowCrossProtocolRedirects = true,
                    )

                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = origin.url("redirect").toString(),
                            protocol = YSourceProtocol.Https,
                            headers =
                                mapOf(
                                    "X-Emby-Token" to "private",
                                    "User-Agent" to "Yfuse-test",
                                ),
                            range = YByteRange(4, 7),
                        ),
                    )

                assertEquals(206, response.statusCode)
                assertEquals(1, response.redirectCount)
                assertEquals(YSourceProtocol.Http, response.finalProtocol)
                assertEquals(true, response.cleartextRedirect)
                assertEquals("private", origin.takeRequest().getHeader("X-Emby-Token"))
                val redirected = media.takeRequest()
                assertEquals(null, redirected.getHeader("X-Emby-Token"))
                assertEquals("Yfuse-test", redirected.getHeader("User-Agent"))
                assertEquals("bytes=4-7", redirected.getHeader("Range"))
                transport.close()
            } finally {
                origin.shutdown()
                media.shutdown()
            }
        }
}
