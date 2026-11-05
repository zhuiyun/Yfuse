package com.yfuse.core.filesource

import com.yfuse.core2.android.AndroidFtpMediaTransport
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import kotlinx.coroutines.test.runTest
import java.io.Closeable
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FtpTransportTest {
    @Test fun mlsd_parses_spaces_unicode_size_and_ignores_parent_links() {
        val entry = parseFtpMlsd("type=file;size=123;modify=20261105000000; 电影 01.mkv")!!
        assertEquals("电影 01.mkv", entry.name)
        assertEquals(123L, entry.sizeBytes)
        assertNotNull(entry.modifiedEpochMs)
        assertNull(parseFtpMlsd("type=cdir; ."))
        assertNull(parseFtpMlsd("type=file; ../escape.mkv"))
    }

    @Test fun ftp_listing_budget_stops_receiving_unbounded_entries() =
        runTest {
            FakeFtp().use { server ->
                FtpSession(server.origin, FileSourceCredentials("user", "pass")).use { session ->
                    val listing = session.list(emptyList(), 2)
                    assertEquals(2, listing.entries.size)
                    assertTrue(listing.truncated)
                }
            }
        }

    @Test fun random_access_transport_uses_rest_and_limits_requested_range() =
        runTest {
            FakeFtp().use { server ->
                val transport = AndroidFtpMediaTransport()
                try {
                    val response =
                        transport.open(
                            YMediaTransportRequest(
                                server.origin + "/movie.mkv",
                                YSourceProtocol.Ftp,
                                range = YByteRange(3, 5),
                                credentials = YTransportCredentials.UsernamePassword("user", "pass"),
                            ),
                        )
                    assertEquals(206, response.statusCode)
                    assertEquals(10L, response.contentLength)
                    val bytes = ByteArray(20)
                    assertEquals(3, transport.read(bytes, 0, bytes.size))
                    assertEquals("345", bytes.take(3).toByteArray().toString(Charsets.UTF_8))
                    assertEquals(-1, transport.read(bytes, 0, bytes.size))
                    assertTrue("REST 3" in server.commands)
                } finally {
                    transport.close()
                }
            }
        }

    private class FakeFtp : Closeable {
        private val control = ServerSocket(0)
        val origin = "ftp://127.0.0.1:${control.localPort}"
        val commands = Collections.synchronizedList(mutableListOf<String>())
        private var data: ServerSocket? = null
        private val worker =
            thread(isDaemon = true) {
                runCatching {
                    control.accept().use { socket ->
                        socket.soTimeout = 3000
                        val input = socket.getInputStream().bufferedReader()
                        val output = socket.getOutputStream().bufferedWriter()

                        fun reply(line: String) {
                            output.write(line + "\r\n")
                            output.flush()
                        }
                        var offset = 0
                        reply("220 Ready")
                        while (true) {
                            val command = input.readLine() ?: break
                            commands += command
                            when (command.substringBefore(' ')) {
                                "USER" -> reply("331 Password")
                                "PASS" -> reply("230 Logged in")
                                "TYPE", "OPTS" -> reply("200 OK")
                                "CWD" -> reply("250 OK")
                                "SIZE" -> reply("213 10")
                                "REST" -> {
                                    offset = command.substringAfter(' ').toInt()
                                    reply("350 Restart accepted")
                                }
                                "EPSV" -> {
                                    data = ServerSocket(0)
                                    reply("229 Entering Extended Passive Mode (|||${data!!.localPort}|)")
                                }
                                "MLSD", "RETR" -> {
                                    reply("150 Opening data")
                                    data!!.use { listener ->
                                        listener.accept().use { connection ->
                                            val body =
                                                if (command.startsWith("MLSD")) {
                                                    (1..10).joinToString("") {
                                                        "type=file;size=10; clip$it.mkv\r\n"
                                                    }
                                                } else {
                                                    "0123456789".drop(offset)
                                                }
                                            connection.getOutputStream().write(body.toByteArray())
                                        }
                                    }
                                    data = null
                                    reply("226 Complete")
                                }
                                else -> reply("500 Unsupported")
                            }
                        }
                    }
                }
            }

        override fun close() {
            runCatching { data?.close() }
            control.close()
            worker.join(3500)
        }
    }
}
