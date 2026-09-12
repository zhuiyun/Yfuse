package com.yfuse.core2.android

import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackTransportAuditTest {
    @Test
    fun changedOriginLengthDoesNotTrapTheNextOpenInOldDiskMetadata() {
        val directory = Files.createTempDirectory("ycore-source-refresh").toFile()
        val identity = YCacheIdentity("scope", "media", "source")
        val media = ByteArray(24) { (it + 30).toByte() }
        val cache = AndroidYCoreBlockCache(directory, identity, 8, 1024)
        cache.validateRepresentation(8, "\"old\"")
        cache.writeBlock(0, ByteArray(8) { 1 }, 8)
        val source =
            AndroidTransportMediaDataSource(
                uri = "http://origin.example/movie.mkv",
                protocol = YSourceProtocol.Http,
                headers = emptyMap(),
                cacheDirectory = directory,
                cacheIdentity = identity,
                cacheMaximumBytes = 1024,
                blockSizeOverride = 8,
                createTransport = { AuditRangeTransport(media) },
            )
        try {
            assertEquals(24L, source.size)
            val output = ByteArray(8)
            assertEquals(8, source.readAt(16, output, 0, 8))
            assertContentEquals(media.copyOfRange(16, 24), output)
        } finally {
            source.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun proxyClosesPartialHttpBodyWithoutAppendingAnotherStatusLine() {
        val directory = Files.createTempDirectory("ycore-proxy-body").toFile()
        val proxy =
            AndroidYCoreHttpProxy(
                userAgent = "audit",
                cacheMaximumBytes = 0,
                cacheDirectory = directory,
                createTransport = {
                    object : YMediaTransport {
                        override val supportedProtocols = setOf(YSourceProtocol.Http)
                        override val features = emptySet<YTransportFeature>()
                        var reads = 0

                        override suspend fun open(request: YMediaTransportRequest) =
                            YMediaTransportResponse(statusCode = 200, contentLength = 12)

                        override suspend fun read(
                            destination: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            if (reads++ > 0) throw IOException("origin disconnected")
                            "data".encodeToByteArray().copyInto(destination, offset)
                            return 4
                        }

                        override suspend fun close() = Unit
                    }
                },
            )
        try {
            val uri = URI(proxy.localUrl("http://origin.example/movie.ts", cacheable = false, cacheIdentity = null))
            val response =
                Socket(uri.host, uri.port).use { socket ->
                    socket.soTimeout = 2000
                    socket.getOutputStream().write(
                        "GET ${uri.rawPath} HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray(),
                    )
                    socket.getInputStream().readBytes().decodeToString()
                }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertEquals("data", response.substringAfter("\r\n\r\n"))
            assertFalse(response.contains("502"))
        } finally {
            proxy.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun discCancellationClosesBlockedIoWithoutWaitingForReadMonitor() {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val source =
            AndroidTransportDiscBlockSource(
                uri = "http://origin.example/movie.iso",
                protocol = YSourceProtocol.Http,
                headers = emptyMap(),
                credentials = null,
                createTransport = {
                    object : YMediaTransport {
                        override val supportedProtocols = setOf(YSourceProtocol.Http)
                        override val features = emptySet<YTransportFeature>()

                        override suspend fun open(request: YMediaTransportRequest) =
                            YMediaTransportResponse(206, 2048, YByteRange(0, 2047))

                        override suspend fun read(
                            destination: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            reading.countDown()
                            check(cancelled.await(3, TimeUnit.SECONDS))
                            throw IOException("cancelled")
                        }

                        override suspend fun close() {
                            cancelled.countDown()
                        }
                    }
                },
            )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Int> { source.readBlocks(0, 1, ByteArray(2048), 0) }
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            source.cancelPendingRead()
            assertEquals(-1, result.get(1, TimeUnit.SECONDS))
        } finally {
            source.close()
            worker.shutdownNow()
        }
    }
}

private class AuditRangeTransport(
    private val bytes: ByteArray,
) : YMediaTransport {
    override val supportedProtocols = setOf(YSourceProtocol.Http)
    override val features = setOf(YTransportFeature.ByteRange)
    private var position = 0
    private var end = 0

    override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
        position = requireNotNull(request.range).startInclusive.toInt()
        end = minOf(bytes.size, (request.range.endInclusive?.plus(1) ?: bytes.size.toLong()).toInt())
        return YMediaTransportResponse(
            206,
            bytes.size.toLong(),
            YByteRange(position.toLong(), end - 1L),
            entityTag = "\"new\"",
        )
    }

    override suspend fun read(
        destination: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= end) return -1
        val count = minOf(length, end - position)
        bytes.copyInto(destination, offset, position, position + count)
        position += count
        return count
    }

    override suspend fun close() = Unit
}
