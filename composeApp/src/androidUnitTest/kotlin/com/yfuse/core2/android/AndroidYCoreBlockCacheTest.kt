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
import kotlin.test.assertTrue

class AndroidYCoreBlockCacheTest {
    @Test
    fun deferred_corruption_cleanup_never_deletes_a_new_valid_commit() {
        val directory = Files.createTempDirectory("ycore-cache-repair-test").toFile()
        val identity = YCacheIdentity("scope", "media", "version")
        val tasks = mutableListOf<() -> Unit>()
        val queue = AndroidCacheWriteQueue(execute = { tasks.add(it) })
        val cache = AndroidYCoreBlockCache(directory, identity, 64, 128, queue)
        try {
            cache.writeBlock(0L, ByteArray(64) { 1 }, 128L)
            val file = blockFile(directory, identity, 0L)
            file.writeBytes(ByteArray(20))
            assertNull(cache.readBlock(0L))
            assertEquals(1, tasks.size)
            // The foreground rejected an old corrupt inode; another writer commits before repair.
            val repaired = ByteArray(64) { 2 }
            cache.writeBlock(0L, repaired, 128L)
            tasks.removeAt(0).invoke()
            assertContentEquals(repaired, cache.readBlock(0L))
            assertEquals(64, cache.cachedBlockLength(0L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun externally_removed_cache_blocks_are_not_reported_as_buffered() {
        val directory = Files.createTempDirectory("ycore-cache-removed-test").toFile()
        val identity = YCacheIdentity("scope", "media", "version")
        val cache = AndroidYCoreBlockCache(directory, identity, 64, 128)
        try {
            cache.writeBlock(0L, ByteArray(64), 128L)
            assertEquals(64, cache.cachedBlockLength(0L))
            assertTrue(blockFile(directory, identity, 0L).delete())
            assertNull(cache.cachedBlockLength(0L))
            assertTrue(cache.awaitPendingWrites(2_000L))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun background_queue_is_bounded_deduplicates_and_releases_capacity_after_failure() {
        val scheduled = mutableListOf<() -> Unit>()
        val queue = AndroidCacheWriteQueue(maximumBytes = 128, maximumEntries = 2, execute = { scheduled.add(it) })
        var writes = 0
        assertTrue(queue.enqueue("a", 64) { writes++ })
        assertTrue(queue.enqueue("a", 64) { error("Duplicate must not run") })
        assertTrue(queue.enqueue("b", 64) { throw java.io.IOException("Full disk") })
        assertFalse(queue.enqueue("c", 64) { writes++ })
        assertEquals(2, scheduled.size)
        scheduled.removeAt(0).invoke()
        kotlin.test.assertFailsWith<java.io.IOException> { scheduled.removeAt(0).invoke() }
        assertTrue(queue.enqueue("c", 128) { writes++ })
        scheduled.removeAt(0).invoke()
        assertEquals(2, writes)
        assertTrue(queue.hasCapacity(128))
    }

    @Test
    fun incremental_eviction_retains_recently_read_blocks_and_does_not_rewrite_unchanged_length() {
        val directory = Files.createTempDirectory("ycore-cache-lru-test").toFile()
        val identity = YCacheIdentity("scope", "media", "version")
        try {
            val cache = AndroidYCoreBlockCache(directory, identity, 64, 128)
            cache.writeBlock(0, ByteArray(64) { 1 }, 256L)
            cache.writeBlock(1, ByteArray(64) { 2 }, 256L)
            val lengthFile = File(blockFile(directory, identity, 0).parentFile, "length")
            assertTrue(lengthFile.setLastModified(10_000L))
            assertContentEquals(ByteArray(64) { 1 }, cache.readBlock(0))
            cache.writeBlock(2, ByteArray(64) { 3 }, 256L)
            assertEquals(10_000L, lengthFile.lastModified())
            assertNull(cache.readBlock(1))
            assertContentEquals(ByteArray(64) { 1 }, cache.readBlock(0))
            assertContentEquals(ByteArray(64) { 3 }, cache.readBlock(2))
        } finally {
            directory.deleteRecursively()
        }
    }

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
            assertTrue(cache.awaitPendingWrites(2_000L))
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
            assertTrue(restrided.awaitPendingWrites(2_000L))
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
