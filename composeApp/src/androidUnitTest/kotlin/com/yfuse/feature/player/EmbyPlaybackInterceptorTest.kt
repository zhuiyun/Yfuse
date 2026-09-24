package com.yfuse.feature.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbyPlaybackInterceptorTest {
    private val client =
        OkHttpClient.Builder().addNetworkInterceptor(EmbyPlaybackInterceptor { "test-version" }).build()

    @Test
    fun rangeRequestsUseTheirOwnAccountAndKeepTheirUrlAndRange() {
        val server = MockWebServer()
        server.start()
        try {
            listOf("a", "b").forEach { account ->
                server.enqueue(MockResponse().setBody("data"))
                val path = "/Videos/movie/stream?api_key=token-$account&UserId=user-$account"
                val request =
                    Request
                        .Builder()
                        .url(server.url(path))
                        .header("Range", "bytes=4-7")
                        .build()
                client.newCall(request).execute().use { assertEquals(200, it.code) }
                val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals(path, received.path)
                assertEquals("bytes=4-7", received.getHeader("Range"))
                assertEquals("token-$account", received.getHeader("X-Emby-Token"))
                assertTrue(assertNotNull(received.getHeader("Authorization")).contains("UserId=\"user-$account\""))
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun sameOriginRedirectKeepsIdentityWhenLocationOmitsToken() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/resolved/original.mkv"))
            server.enqueue(MockResponse().setBody("data"))
            val original = server.url("/Videos/1/stream?api_key=token-a&UserId=user-a")
            client.newCall(Request.Builder().url(original).build()).execute().close()
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/resolved/original.mkv", received.path)
            assertEquals("token-a", received.getHeader("X-Emby-Token"))
            assertTrue(assertNotNull(received.getHeader("Authorization")).contains("UserId=\"user-a\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun crossOriginRedirectStripsTokenAndAllClientIdentityHeaders() {
        val origin = MockWebServer()
        val cdn = MockWebServer()
        origin.start()
        cdn.start()
        try {
            val signed = cdn.url("/video?signature=a%2Bb")
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", signed))
            cdn.enqueue(MockResponse().setBody("data"))
            val original = origin.url("/Videos/1/stream?api_key=private-token&UserId=private-user")
            client.newCall(Request.Builder().url(original).build()).execute().close()
            val first = assertNotNull(origin.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("private-token", first.getHeader("X-Emby-Token"))
            val redirected = assertNotNull(cdn.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("/video?signature=a%2Bb", redirected.path)
            assertNull(redirected.getHeader("Authorization"))
            assertTrue(redirected.headers.names().none { it.startsWith("X-Emby-", ignoreCase = true) })
        } finally {
            origin.shutdown()
            cdn.shutdown()
        }
    }

    @Test
    fun sameOriginRedirectWithDifferentTokenDoesNotReceiveTheOldIdentity() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/video?api_key=other"))
            server.enqueue(MockResponse().setBody("data"))
            val original = server.url("/Videos/1/stream?api_key=token-a&UserId=user-a")
            client.newCall(Request.Builder().url(original).build()).execute().close()
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val redirected = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertNull(redirected.getHeader("X-Emby-Token"))
            assertNull(redirected.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun explicitGatewayAuthorizationIsNotOverwritten() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("data"))
            val request =
                Request
                    .Builder()
                    .url(server.url("/Videos/1/stream?api_key=token-a&UserId=user-a"))
                    .header("Authorization", "Basic gateway-credential")
                    .build()
            client.newCall(request).execute().close()
            val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals("Basic gateway-credential", received.getHeader("Authorization"))
            assertEquals("token-a", received.getHeader("X-Emby-Token"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun signedCdnRequestDoesNotReceiveEmbyHeaders() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("data"))
            val path = "/video?signature=a%2Bb"
            client.newCall(Request.Builder().url(server.url(path)).build()).execute().close()
            val received = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertEquals(path, received.path)
            assertNull(received.getHeader("Authorization"))
            assertTrue(received.headers.names().none { it.startsWith("X-Emby-", ignoreCase = true) })
        } finally {
            server.shutdown()
        }
    }
}
