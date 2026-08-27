package com.yfuse.core2.android

import com.yfuse.core2.network.YCacheIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

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
}
