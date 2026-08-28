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
            val cache = AndroidYCoreBlockCache(directory, identity, maximumBytes = 1024L * 1024L)
            val expected = "verified media bytes".encodeToByteArray()
            cache.writeBlock(index = 0L, bytes = expected, contentLength = expected.size.toLong())
            assertContentEquals(expected, cache.readBlock(index = 0L, maximumBlockBytes = 1024))

            val block =
                File(
                    directory,
                    "ycore-media-v2/${yCoreCacheDirectoryKey(identity)}/block-0.bin",
                )
            val corrupted = block.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
            block.writeBytes(corrupted)

            assertNull(cache.readBlock(index = 0L, maximumBlockBytes = 1024))
            assertFalse(block.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
