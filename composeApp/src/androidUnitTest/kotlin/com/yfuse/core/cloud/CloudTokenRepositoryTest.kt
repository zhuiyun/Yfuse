package com.yfuse.core.cloud

import com.yfuse.core.security.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudTokenRepositoryTest {
    private class MemoryStore : SecureStore {
        val entries = mutableMapOf<String, ByteArray>()

        override fun get(key: String) = entries[key]?.copyOf()

        override fun put(
            key: String,
            value: ByteArray,
        ) {
            entries[key] = value.copyOf()
        }

        override fun remove(key: String) = entries.remove(key) != null

        override fun clear() {
            entries.clear()
        }
    }

    @Test fun aDifferentAccountCannotReadTheRotatedTokenAndRotationSurvivesReopening() {
        val store = MemoryStore()
        val key = cloudDigest("token-test-source") + ":account-a"
        EncryptedCloudTokenRepository(store).write(key, CloudDriveCredentials(accessToken = "rotated"))
        assertEquals("rotated", EncryptedCloudTokenRepository(store).read(key)?.accessToken)
        assertNull(EncryptedCloudTokenRepository(store).read(cloudDigest("token-test-source") + ":account-b"))
    }

    @Test fun deletionPreventsAnInFlightRefreshFromResurrectingCredentials() {
        val store = MemoryStore()
        val sourceId = "token-deletion-source"
        val key = cloudDigest(sourceId) + ":account"
        val first = EncryptedCloudTokenRepository(store)
        first.write(key, CloudDriveCredentials(refreshToken = "original"))
        EncryptedCloudTokenRepository(store).forget(sourceId)
        first.write(key, CloudDriveCredentials(refreshToken = "late-rotation"))
        assertTrue(store.entries.isEmpty())
        assertNull(first.read(key))
    }
}
