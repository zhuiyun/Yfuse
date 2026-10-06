package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportCredentials
import com.yfuse.core2.network.YTransportFeature
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** SMB shares and on-device documents reach FFmpeg only as loopback HTTP served by YCore. */
class AndroidProxyFileProtocolTest {
    @Test
    fun an_unencoded_smb_path_is_served_with_its_login() {
        val payload = ByteArray(4_096) { (it % 251).toByte() }
        val upstream = MemoryTransport(payload)
        val login = YTransportCredentials.UsernamePassword("viewer", "secret", "WORKGROUP")
        withProxy({ upstream }) { proxy ->
            // jcifs reads SMB paths literally, so they are never percent-encoded.
            val source = "smb://nas.local/Movies/Some Film (2020).mkv"

            val local = proxy.localUrl(source, credentials = login, cacheable = false, cacheIdentity = null)

            assertNotEquals(source, local)
            assertTrue(local.startsWith("http://127.0.0.1:"))
            assertContentEquals(payload, read(local))
            val request = upstream.requests.first()
            assertEquals(source, request.uri)
            assertEquals(YSourceProtocol.Smb, request.protocol)
            assertSame(login, request.credentials)
        }
    }

    @Test
    fun documents_are_served_but_never_copied_into_the_block_cache() {
        val payload = ByteArray(256 * 1024) { (it % 239).toByte() }
        val identity = YCacheIdentity(scope = "test", mediaId = "item")
        val sources =
            listOf(
                "https://media.test/film.mkv" to true,
                "content://media/external/video/1" to false,
            )
        for ((source, expectBlocks) in sources) {
            val directory = Files.createTempDirectory("ycore-proxy-document").toFile()
            val upstream = MemoryTransport(payload)
            val proxy =
                AndroidYCoreHttpProxy(
                    userAgent = "Yfuse-test",
                    cacheMaximumBytes = 64L * 1024L * 1024L,
                    createTransport = { upstream },
                    cacheDirectory = directory,
                    isMeteredNetwork = { false },
                )

            fun blocks() = directory.walkTopDown().count { it.isFile && it.name.startsWith("block-") }
            try {
                val local = proxy.localUrl(source, cacheable = true, cacheIdentity = identity)
                assertContentEquals(payload, read(local, range = "bytes=0-"))
                assertEquals(
                    if (source.startsWith("content")) YSourceProtocol.Local else YSourceProtocol.Https,
                    upstream.requests.first().protocol,
                )
                // Blocks are committed off the serving thread; give the remote control time to land.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (expectBlocks && blocks() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            } finally {
                proxy.close()
            }
            assertEquals(expectBlocks, blocks() > 0, "cache blocks for $source")
            directory.deleteRecursively()
        }
    }

    @Test
    fun an_upstream_that_ignores_ranges_is_streamed_instead_of_refused() {
        val payload = ByteArray(300_000) { (it % 233).toByte() }
        val upstream = WholeBodyTransport(payload)
        withProxy({ upstream }) { proxy ->
            val local = proxy.localUrl("https://media.test/transcode.mp4", cacheable = false, cacheIdentity = null)
            val connection = URL(local).openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 5_000
            connection.setRequestProperty("Range", "bytes=0-")
            try {
                assertEquals(200, connection.responseCode)
                // Told "none", FFmpeg treats the body as a stream and never asks for an offset.
                assertEquals("none", connection.getHeaderField("Accept-Ranges"))
                assertContentEquals(payload, connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun the_document_transport_reads_from_any_offset() {
        val file = File.createTempFile("ycore-document", ".bin")
        val payload = ByteArray(10_000) { (it * 7).toByte() }
        file.writeBytes(payload)
        try {
            val transport =
                AndroidContentMediaTransport { _ ->
                    val access = RandomAccessFile(file, "r")
                    AndroidOpenedDocument(access.channel, access.length(), seekable = true, release = access::close)
                }
            runBlocking {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = "content://provider/document/1",
                            protocol = YSourceProtocol.Local,
                            range = YByteRange(9_000L, null),
                        ),
                    )
                assertEquals(206, response.statusCode)
                assertEquals(10_000L, response.contentLength)
                assertEquals(YByteRange(9_000L, 9_999L), response.acceptedRange)
                assertContentEquals(payload.copyOfRange(9_000, 10_000), transport.readAll())
                transport.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun a_streamed_document_skips_forward_instead_of_seeking() {
        val file = File.createTempFile("ycore-document", ".bin")
        val payload = ByteArray(200_000) { (it % 97).toByte() }
        file.writeBytes(payload)
        try {
            val transport =
                AndroidContentMediaTransport { _ ->
                    val access = RandomAccessFile(file, "r")
                    // A provider pipe: no length, and only forward reads.
                    AndroidOpenedDocument(access.channel, length = null, seekable = false, release = access::close)
                }
            runBlocking {
                val response =
                    transport.open(
                        YMediaTransportRequest(
                            uri = "content://provider/document/2",
                            protocol = YSourceProtocol.Local,
                            range = YByteRange(150_000L, null),
                        ),
                    )
                assertNull(response.contentLength)
                assertTrue(YTransportFeature.RandomAccess !in response.features)
                assertContentEquals(payload.copyOfRange(150_000, 200_000), transport.readAll())
                transport.close()
            }
        } finally {
            file.delete()
        }
    }

    private suspend fun YMediaTransport.readAll(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = read(buffer, 0, buffer.size)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun read(
        url: String,
        range: String? = null,
    ): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 5_000
        if (range != null) connection.setRequestProperty("Range", range)
        return try {
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private inline fun withProxy(
        noinline createTransport: () -> YMediaTransport,
        block: (AndroidYCoreHttpProxy) -> Unit,
    ) {
        val directory = Files.createTempDirectory("ycore-proxy-file-protocol").toFile()
        val proxy =
            AndroidYCoreHttpProxy(
                userAgent = "Yfuse-test",
                cacheMaximumBytes = 0L,
                createTransport = createTransport,
                cacheDirectory = directory,
                isMeteredNetwork = { false },
            )
        try {
            block(proxy)
        } finally {
            proxy.close()
            directory.deleteRecursively()
        }
    }

    /** Answers every request, ranged or not, with the whole body, as a progressive transcode does. */
    private class WholeBodyTransport(
        private val payload: ByteArray,
    ) : YMediaTransport {
        override val supportedProtocols = YSourceProtocol.entries.toSet()
        override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
        private var position = 0

        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
            position = 0
            return YMediaTransportResponse(
                statusCode = 200,
                contentLength = payload.size.toLong(),
                acceptedRange = null,
                features = features,
            )
        }

        override suspend fun read(
            destination: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= payload.size) return -1
            val count = minOf(length, payload.size - position)
            payload.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun close() = Unit
    }

    /** Serves [payload] for any address, honouring the requested start offset. */
    private class MemoryTransport(
        private val payload: ByteArray,
    ) : YMediaTransport {
        override val supportedProtocols = YSourceProtocol.entries.toSet()
        override val features = setOf(YTransportFeature.ByteRange, YTransportFeature.RandomAccess)
        val requests = CopyOnWriteArrayList<YMediaTransportRequest>()
        private var position = 0

        override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
            requests += request
            position = request.range?.startInclusive?.toInt() ?: 0
            return YMediaTransportResponse(
                statusCode = if (request.range == null) 200 else 206,
                contentLength = payload.size.toLong(),
                acceptedRange = request.range?.boundedTo(payload.size.toLong()),
                features = features,
            )
        }

        override suspend fun read(
            destination: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (position >= payload.size) return -1
            val count = minOf(length, payload.size - position)
            payload.copyInto(destination, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun close() = Unit
    }
}
