package com.yfuse.core2.android

import com.yfuse.core.playback.PLAYBACK_PROXY_HEADER_TIMEOUT_MS
import com.yfuse.core.playback.PlaybackProxyAdmission
import com.yfuse.core.playback.PlaybackProxyConnections
import com.yfuse.feature.player.AndroidPlaybackHttpProxy
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidPlaybackProxyAdmissionTest {
    @Test
    fun both_proxy_engines_enforce_instance_and_shared_process_admission() {
        val process = PlaybackProxyConnections(2)
        val legacyAdmission = PlaybackProxyAdmission(1, process)
        val coreAdmission = PlaybackProxyAdmission(2, process)
        AndroidPlaybackHttpProxy(null, "test", 0, legacyAdmission).use { legacy ->
            withCoreProxy(admission = coreAdmission) { core ->
                val legacyUri = URI(legacy.localUrl("https://media.test/movie.mp4"))
                val coreUri =
                    URI(core.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null))
                Socket(legacyUri.host, legacyUri.port).use { legacyClient ->
                    legacyClient.sendRequestLine(legacyUri)
                    awaitConnections(legacyAdmission, 1)
                    Socket(coreUri.host, coreUri.port).use { coreClient ->
                        coreClient.sendRequestLine(coreUri)
                        awaitConnections(coreAdmission, 1)
                        Socket(legacyUri.host, legacyUri.port).use { client ->
                            client.sendRequestLine(legacyUri)
                            assertDisconnected(client)
                        }
                        Socket(coreUri.host, coreUri.port).use { client ->
                            client.sendRequestLine(coreUri)
                            assertAnsweredBusy(client)
                        }
                        assertEquals(1, legacyAdmission.activeConnections)
                        assertEquals(1, coreAdmission.activeConnections)
                    }
                }
                legacy.close()
                core.close()
                awaitConnections(legacyAdmission, 0)
                awaitConnections(coreAdmission, 0)
            }
        }
    }

    @Test
    fun a_connection_that_sends_no_request_takes_no_playback_slot() {
        val coreAdmission = PlaybackProxyAdmission(1, PlaybackProxyConnections(4))
        withCoreProxy(admission = coreAdmission) { core ->
            val coreUri = URI(core.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null))
            Socket(coreUri.host, coreUri.port).use {
                awaitPending(coreAdmission, 1)
                assertEquals(0, coreAdmission.activeConnections)
                // The player's own request still gets the one playback slot.
                Socket(coreUri.host, coreUri.port).use { player ->
                    player.sendRequestLine(coreUri)
                    awaitConnections(coreAdmission, 1)
                }
            }
        }
    }

    @Test
    fun a_request_for_an_unknown_route_is_refused_without_a_playback_slot() {
        val coreAdmission = PlaybackProxyAdmission(1, PlaybackProxyConnections(4))
        withCoreProxy(admission = coreAdmission) { core ->
            val coreUri = URI(core.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null))
            Socket(coreUri.host, coreUri.port).use { client ->
                client.getOutputStream().write("GET /not-a-route HTTP/1.1\r\n\r\n".encodeToByteArray())
                client.soTimeout = 2_000
                val response = client.getInputStream().readBytes().decodeToString()
                assertTrue(response.startsWith("HTTP/1.1 404 "), response)
            }
            assertEquals(0, coreAdmission.activeConnections)
        }
    }

    @Test
    fun both_proxy_engines_disconnect_incomplete_headers_at_the_total_deadline() {
        AndroidPlaybackHttpProxy(null, "test", 0, headerTimeoutMs = 100L).use { proxy ->
            assertIncompleteHeadersExpire(URI(proxy.localUrl("https://media.test/movie.mp4")))
        }
        withCoreProxy(headerTimeoutMs = 100L) { proxy ->
            assertIncompleteHeadersExpire(
                URI(proxy.localUrl("https://media.test/movie.mp4", cacheable = false, cacheIdentity = null)),
            )
        }
    }

    private fun withCoreProxy(
        admission: PlaybackProxyAdmission = PlaybackProxyAdmission(),
        headerTimeoutMs: Long = PLAYBACK_PROXY_HEADER_TIMEOUT_MS,
        block: (AndroidYCoreHttpProxy) -> Unit,
    ) {
        val directory = Files.createTempDirectory("ycore-proxy-admission").toFile()
        try {
            AndroidYCoreHttpProxy(
                userAgent = "test",
                cacheMaximumBytes = 0,
                createTransport = { error("An incomplete request must not open upstream") },
                cacheDirectory = directory,
                isMeteredNetwork = { false },
                connectionAdmission = admission,
                headerTimeoutMs = headerTimeoutMs,
            ).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertIncompleteHeadersExpire(uri: URI) {
        Socket(uri.host, uri.port).use { client ->
            client.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nX: pending".encodeToByteArray())
            assertDisconnected(client)
        }
    }

    /** FFmpeg treats a bare close as a failed read; the YCore proxy answers a retryable 503 first. */
    private fun assertAnsweredBusy(client: Socket) {
        client.soTimeout = 2_000
        val response = client.getInputStream().readBytes().decodeToString()
        assertTrue(response.startsWith("HTTP/1.1 503 Service Unavailable\r\n"), response)
        assertTrue("\r\nRetry-After: 1\r\n" in response, response)
    }

    private fun assertDisconnected(client: Socket) {
        client.soTimeout = 2_000
        val eof =
            try {
                client.getInputStream().read() == -1
            } catch (_: SocketException) {
                true
            }
        assertTrue(eof, "Rejected or expired connection must close without opening an upstream")
    }

    /** A request line for a route the proxy handed out, with its headers still to come. */
    private fun Socket.sendRequestLine(uri: URI) {
        getOutputStream().apply {
            write("GET ${uri.rawPath} HTTP/1.1\r\n".encodeToByteArray())
            flush()
        }
    }

    private fun awaitPending(
        admission: PlaybackProxyAdmission,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (admission.pendingConnections != expected && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(expected, admission.pendingConnections)
    }

    private fun awaitConnections(
        admission: PlaybackProxyAdmission,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (admission.activeConnections != expected && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(expected, admission.activeConnections)
    }
}
