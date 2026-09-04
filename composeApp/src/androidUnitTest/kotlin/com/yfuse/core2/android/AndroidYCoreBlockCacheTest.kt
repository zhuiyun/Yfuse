package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class AndroidYCoreBlockCacheTest {
    @Test
    fun directory_key_is_stable_and_contains_no_provider_identity() {
        val identity =
            YCacheIdentity(
                scope = "server-secret-name",
                mediaId = "episode-42",
                version = "source-a",
            )
        val first = yCoreCacheDirectoryKey(identity)

        assertEquals(64, first.length)
        assertEquals(first, yCoreCacheDirectoryKey(identity))
        assertFalse(first.contains(identity.scope))
        assertFalse(first.contains(identity.mediaId))
        assertNotEquals(first, yCoreCacheDirectoryKey(identity.copy(version = "source-b")))
    }

    @Test
    fun corrupted_block_is_rejected_and_removed() {
        val directory = Files.createTempDirectory("ycore-cache-test").toFile()
        val identity = YCacheIdentity(scope = "scope", mediaId = "media", version = "version")
        try {
            val cache = cacheFor(directory, identity, blockSizeBytes = 1024)
            val expected = "verified media bytes".encodeToByteArray()
            cache.writeBlock(index = 0L, bytes = expected, contentLength = expected.size.toLong())
            assertContentEquals(expected, cache.readBlock(index = 0L))

            val block = blockFile(directory, identity, index = 0L)
            val corrupted = block.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            block.writeBytes(corrupted)

            assertNull(cache.readBlock(index = 0L))
            assertFalse(block.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * A block file is named by index alone, so the same name means different bytes of the media
     * once the stride changes. Without the stride in the header the old block decodes cleanly and
     * its checksum still matches, handing the decoder content from the wrong offset.
     */
    @Test
    fun a_block_written_under_a_different_stride_is_refused() {
        val directory = Files.createTempDirectory("ycore-cache-test").toFile()
        val identity = YCacheIdentity(scope = "scope", mediaId = "media", version = "version")
        try {
            val written = ByteArray(512) { index -> index.toByte() }
            val original = cacheFor(directory, identity, blockSizeBytes = 4096)
            original.writeBlock(index = 1L, bytes = written, contentLength = 1_048_576L)
            // The rejection below has to be about the stride, so prove the block is readable first.
            assertContentEquals(written, original.readBlock(index = 1L))

            val restrided = cacheFor(directory, identity, blockSizeBytes = 8192)

            assertNull(restrided.readBlock(index = 1L))
            // Every block in this directory describes offsets the new stride cannot address, so an
            // unreadable one is dropped rather than left to occupy the budget forever.
            assertFalse(blockFile(directory, identity, index = 1L).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun blocks_from_a_superseded_cache_format_are_discarded() {
        val directory = Files.createTempDirectory("ycore-cache-test").toFile()
        val identity = YCacheIdentity(scope = "scope", mediaId = "media", version = "version")
        try {
            val stale = File(directory, "ycore-media-v2/${yCoreCacheDirectoryKey(identity)}")
            stale.mkdirs()
            File(stale, "block-0.bin").writeBytes(ByteArray(64))

            cacheFor(directory, identity, blockSizeBytes = 4096)

            assertFalse(File(directory, "ycore-media-v2").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun cacheFor(
        directory: File,
        identity: YCacheIdentity,
        blockSizeBytes: Int,
    ) = AndroidYCoreBlockCache(
        cacheDirectory = directory,
        identity = identity,
        blockSizeBytes = blockSizeBytes,
        maximumBytes = 1024L * 1024L,
    )

    private fun blockFile(
        directory: File,
        identity: YCacheIdentity,
        index: Long,
    ) = File(directory, "ycore-media-v3/${yCoreCacheDirectoryKey(identity)}/block-$index.bin")
}
