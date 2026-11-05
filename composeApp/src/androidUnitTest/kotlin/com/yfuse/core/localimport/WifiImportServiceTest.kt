package com.yfuse.core.localimport

import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiImportServiceTest {
    @Test fun upload_requires_pairing_rejects_traversal_and_budget_then_publishes_playable_file() =
        runBlocking {
            val folder = Files.createTempDirectory("yfuse-wifi-test").toFile()
            val service =
                AndroidWifiImportService(
                    { folder },
                    { InetAddress.getByName("127.0.0.1") },
                    maxFileBytes = 20,
                    maxSessionBytes = 30,
                )
            try {
                service.start()
                val address = service.state.value.address!!
                val code = service.state.value.pairingCode!!
                assertTrue(request(address, "movie.mp4", "wrong", "abc".toByteArray()).startsWith("HTTP/1.1 403"))
                assertTrue(request(address, "../escape.mp4", code, "abc".toByteArray()).startsWith("HTTP/1.1 400"))
                assertTrue(request(address, "large.mp4", code, ByteArray(21)).startsWith("HTTP/1.1 413"))
                assertTrue(folder.listFiles().orEmpty().isEmpty())
                assertTrue(request(address, "电影 01.mp4", code, "video-data".toByteArray()).startsWith("HTTP/1.1 201"))
                val imported =
                    service.state.value.files
                        .single()
                assertEquals("电影 01.mp4", imported.name)
                assertEquals("video-data", File(URI(imported.uri)).readText())
                service.stop()
                assertFalse(service.state.value.running)
                val reopened = AndroidWifiImportService({ folder })
                reopened.load()
                assertEquals(
                    imported,
                    reopened.state.value.files
                        .single(),
                )
                reopened.remove(imported.id)
                assertTrue(folder.listFiles().orEmpty().isEmpty())
            } finally {
                service.stop()
                folder.listFiles().orEmpty().forEach(File::delete)
                folder.delete()
            }
        }

    @Test fun stopping_session_aborts_incomplete_body_and_removes_partial() =
        runBlocking {
            val folder = Files.createTempDirectory("yfuse-wifi-cancel").toFile()
            val service = AndroidWifiImportService({ folder }, { InetAddress.getByName("127.0.0.1") })
            try {
                service.start()
                val uri = URI(service.state.value.address!!)
                Socket(uri.host, uri.port).use { socket ->
                    val head =
                        "PUT /upload?name=partial.mp4 HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" +
                            "X-Yfuse-Pair-Code: ${service.state.value.pairingCode}\r\nContent-Length: 100\r\n\r\nab"
                    socket.getOutputStream().apply {
                        write(head.toByteArray())
                        flush()
                    }
                    awaitCondition { service.state.value.receiving }
                    service.stop()
                    awaitCondition { folder.listFiles().orEmpty().isEmpty() }
                }
                assertTrue(
                    service.state.value.files
                        .isEmpty(),
                )
                assertFalse(service.state.value.running)
            } finally {
                service.stop()
                folder.listFiles().orEmpty().forEach(File::delete)
                folder.delete()
            }
        }

    private fun request(
        address: String,
        name: String,
        code: String,
        body: ByteArray,
    ): String {
        val uri = URI(address)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = 3000
            val encodedName = URLEncoder.encode(name, "UTF-8")
            val header =
                "PUT /upload?name=$encodedName HTTP/1.1\r\nHost: ${uri.rawAuthority}\r\n" +
                    "Origin: $address\r\nX-Yfuse-Pair-Code: $code\r\nContent-Length: ${body.size}\r\n\r\n"
            socket.getOutputStream().apply {
                write(header.toByteArray())
                write(body)
                flush()
            }
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition())
    }
}
