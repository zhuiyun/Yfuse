package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.SecureStoreException
import com.yfuse.core.security.TestSecureStore
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fault-injection audit probe: asserts the current destructive behavior, not desired behavior. */
class ServerRegistryTransientReadAuditTest {
    @Test
    fun transient_read_error_currently_deletes_the_persisted_session() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        ServerRegistry(settings, secrets).addOrUpdate(
            SavedServer("audit", "https://audit.example", "Audit", "user", "User", "synthetic-token"),
        )
        assertTrue(secrets.storedKeys().isNotEmpty())
        val unavailable =
            object : SecureStore by secrets {
                override fun get(key: String): ByteArray? = throw SecureStoreException("temporary keystore outage")
            }
        val interrupted = ServerRegistry(settings, unavailable)
        assertTrue(interrupted.data.value.servers.isEmpty())
        assertTrue(secrets.storedKeys().isEmpty(), "Temporary read error also deleted the encrypted token")
        val recovered = ServerRegistry(settings, secrets)
        assertTrue(recovered.data.value.servers.isEmpty(), "Session stays lost after recovery")
    }
}
