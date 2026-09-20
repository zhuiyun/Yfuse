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
                Socket(legacyUri.host, legacyUri.port).use {
                    awaitConnections(legacyAdmission, 1)
                    Socket(coreUri.host, coreUri.port).use {
                        awaitConnections(coreAdmission, 1)
                        Socket(legacyUri.host, legacyUri.port).use(::assertDisconnected)
                        Socket(coreUri.host, coreUri.port).use(::assertDisconnected)
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

    private fun awaitConnections(
        admission: PlaybackProxyAdmission,
        expected: Int,
    ) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (admission.activeConnections != expected && System.nanoTime() < deadline) Thread.sleep(1L)
        assertEquals(expected, admission.activeConnections)
    }
}
