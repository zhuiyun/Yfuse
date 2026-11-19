package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackProxyRequest
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.Socket
import java.util.concurrent.TimeUnit

class PlaybackHttp2BodyRecoveryTest {
    @Test
    fun stalledHttp2BodyResumesOnIndependentTlsConnectionWithTheSamePinAndMissingRange() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.getHeader("If-Range") == null ->
                                partial("bytes 2-7/12", "cdefgh").throttleBody(2, 6, TimeUnit.SECONDS)
                            request.getHeader("X-Test-Negotiated-Protocol") == Protocol.HTTP_2.toString() ->
                                partial("bytes 4-7/12", "efgh").setHeadersDelay(6, TimeUnit.SECONDS)
                            else -> partial("bytes 4-7/12", "efgh")
                        }
                }
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                    .certificatePinner(
                        CertificatePinner
                            .Builder()
                            .add(
                                "localhost",
                                CertificatePinner.pin(certificate.certificate),
                            ).build(),
                    ).readTimeout(1500, TimeUnit.MILLISECONDS)
                    .addNetworkInterceptor { chain ->
                        val negotiated = requireNotNull(chain.connection()).protocol().toString()
                        chain.proceed(
                            chain
                                .request()
                                .newBuilder()
                                .header("X-Test-Negotiated-Protocol", negotiated)
                                .build(),
                        )
                    }.build()
            val request =
                Request
                    .Builder()
                    .url(server.url("/movie"))
                    .header("Range", "bytes=2-7")
                    .build()
            Socket().use { downstream ->
                PlaybackCacheCalls(client).forRequest(PlaybackProxyRequest(downstream)).newCall(request).execute().use {
                    assertEquals(Protocol.HTTP_2, it.protocol)
                    assertEquals("cdefgh", it.body.string())
                }
            }
            val initial = server.takeRequest()
            val resume = server.takeRequest()
            assertEquals(Protocol.HTTP_2.toString(), initial.getHeader("X-Test-Negotiated-Protocol"))
            assertEquals(Protocol.HTTP_1_1.toString(), resume.getHeader("X-Test-Negotiated-Protocol"))
            assertEquals(0, resume.sequenceNumber)
            assertEquals("bytes=4-7", resume.getHeader("Range"))
            assertEquals("\"original-movie\"", resume.getHeader("If-Range"))
            assertEquals(2, server.requestCount)
        }
    }

    private fun partial(
        range: String,
        value: String,
    ): MockResponse =
        MockResponse()
            .setResponseCode(206)
            .setHeader("Content-Range", range)
            .setHeader("ETag", "\"original-movie\"")
            .setBody(value)
}
