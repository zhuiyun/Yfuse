package com.yfuse.core2.android

import com.yfuse.core2.api.YMediaItem
import com.yfuse.core2.network.YByteRange
import com.yfuse.core2.network.YCacheIdentity
import com.yfuse.core2.network.YMediaTransport
import com.yfuse.core2.network.YMediaTransportRequest
import com.yfuse.core2.network.YMediaTransportResponse
import com.yfuse.core2.network.YSourceProtocol
import com.yfuse.core2.network.YTransportFeature
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NextItemRangeReuseTest {
    @Test fun warmed_ranges_are_consumed_by_a_new_ycore_reader_with_the_same_cache_identity() {
        val directory = Files.createTempDirectory("ycore-next-ranges").toFile()
        val media = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        val transferred = AtomicLong()
        val memory = PlaybackMemoryPool(32L * 1024 * 1024)
        val item =
            YMediaItem(
                "next",
                "https://example.invalid/episode.mkv",
                headers = mapOf("Authorization" to "test-only"),
                cacheIdentity = YCacheIdentity("scope", "media", "version"),
                cacheMaximumBytes = 32L * 1024 * 1024,
            )

        fun transport(): YMediaTransport =
            object : YMediaTransport {
                override val supportedProtocols = setOf(YSourceProtocol.Https)
                override val features = setOf(YTransportFeature.ByteRange)
                var position = 0
                var end = 0

                override suspend fun open(request: YMediaTransportRequest): YMediaTransportResponse {
                    assertEquals(item.headers, request.headers)
                    val range = requireNotNull(request.range)
                    position = range.startInclusive.toInt()
                    end = minOf(media.size, ((range.endInclusive ?: media.lastIndex.toLong()) + 1L).toInt())
                    return YMediaTransportResponse(
                        206,
                        media.size.toLong(),
                        YByteRange(position.toLong(), end.toLong() - 1L),
                    )
                }

                override suspend fun read(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (position >= end) return -1
                    val count = minOf(length, end - position)
                    media.copyInto(destination, offset, position, position + count)
                    position += count
                    transferred.addAndGet(count.toLong())
                    return count
                }

                override suspend fun close() = Unit
            }
        try {
            AndroidProbeBudget().use { budget ->
                val warmed =
                    warmNextItemBytes(directory, item, budget, ::transport) {
                        memory.acquire(PlaybackBufferKind.Preload, 8L * 1024 * 1024)
                    }
                assertTrue(warmed >= 4L * 1024 * 1024)
            }
            assertTrue(transferred.get() > 0L)
            transferred.set(0)
            AndroidTransportMediaDataSource(
                uri = item.uri,
                protocol = YSourceProtocol.Https,
                headers = item.headers,
                cacheDirectory = directory,
                cacheIdentity = item.cacheIdentity,
                cacheMaximumBytes = item.cacheMaximumBytes,
                createTransport = ::transport,
                allowsSpeculativeWork = { false },
            ).use { reader ->
                val buffer = ByteArray(64 * 1024)
                for (offset in listOf(512 * 1024, 2 * 1024 * 1024, media.size - buffer.size)) {
                    assertEquals(buffer.size, reader.readAt(offset.toLong(), buffer, 0, buffer.size))
                    assertContentEquals(media.copyOfRange(offset, offset + buffer.size), buffer)
                }
                assertTrue(reader.awaitCacheWrites(2_000L))
            }
            assertEquals(0L, transferred.get(), "Prepared file ranges should not be downloaded by the new reader")
        } finally {
            directory.deleteRecursively()
        }
    }
}
