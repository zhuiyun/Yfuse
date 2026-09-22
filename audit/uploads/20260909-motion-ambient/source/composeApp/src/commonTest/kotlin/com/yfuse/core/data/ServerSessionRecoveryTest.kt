package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.SecureStoreException
import com.yfuse.core.security.TestSecureStore
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSessionRecoveryTest {
    private fun server(id: String) =
        SavedServer(
            id = id,
            baseUrl = "https://$id.example",
            serverName = id,
            userId = "user",
            userName = "User",
            accessToken = "token-$id",
            cloudAccessToken = "cloud-$id",
            cloudOwnerAccessToken = "owner-$id",
        )

    @Test
    fun temporary_failure_in_any_secret_slot_preserves_every_session_and_cache() {
        listOf("emby-token:", "plex-account-token:", "plex-owner-token:").forEach { prefix ->
            val settings = MapSettings()
            val secrets = TestSecureStore()
            val original = server("a")
            ServerRegistry(settings, secrets).addOrUpdate(original)
            settings.putString("library.cache.a", "cached-library")
            val metadata = settings.getStringOrNull("servers.data")
            val keys = secrets.storedKeys()
            val unavailable =
                object : SecureStore by secrets {
                    override fun get(key: String): ByteArray? {
                        if (key.startsWith(prefix)) throw SecureStoreException("temporarily unavailable")
                        return secrets.get(key)
                    }
                }

            repeat(3) {
                assertFailsWith<ServerSessionRestoreException> { ServerRegistry(settings, unavailable) }
                assertEquals(metadata, settings.getStringOrNull("servers.data"))
                assertEquals(keys, secrets.storedKeys())
                assertEquals("cached-library", settings.getStringOrNull("library.cache.a"))
            }
            assertEquals(original, ServerRegistry(settings, secrets).defaultServer)
        }
    }

    @Test
    fun cleanup_of_an_earlier_corrupt_session_waits_for_the_entire_read() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val registry = ServerRegistry(settings, secrets)
        registry.addOrUpdate(server("a"))
        val corruptKey = secrets.storedKeys().first { it.startsWith("emby-token:") }
        val firstKeys = secrets.storedKeys()
        registry.addOrUpdate(server("b"))
        val unavailableKey = (secrets.storedKeys() - firstKeys).first { it.startsWith("emby-token:") }
        secrets.corruptedKeys += corruptKey
        val keys = secrets.storedKeys()
        val metadata = settings.getStringOrNull("servers.data")
        settings.putString("library.cache.a", "cache-a")
        settings.putString("library.cache.b", "cache-b")
        val unavailable =
            object : SecureStore by secrets {
                override fun get(key: String): ByteArray? {
                    if (key == unavailableKey) throw SecureStoreException("temporary outage")
                    return secrets.get(key)
                }
            }

        assertFailsWith<ServerSessionRestoreException> { ServerRegistry(settings, unavailable) }
        assertEquals(keys, secrets.storedKeys())
        assertEquals(metadata, settings.getStringOrNull("servers.data"))
        assertEquals("cache-a", settings.getStringOrNull("library.cache.a"))
        assertEquals("cache-b", settings.getStringOrNull("library.cache.b"))

        val restored = ServerRegistry(settings, secrets)
        val restoredData = restored.data.value
        assertEquals(listOf("b"), restoredData.servers.map { it.id })
        assertFalse(corruptKey in secrets.storedKeys())
        assertEquals("cache-b", settings.getStringOrNull("library.cache.b"))
    }

    @Test
    fun failed_koin_restore_publishes_no_empty_registry_and_starts_services_once_after_retry() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        ServerRegistry(settings, secrets).addOrUpdate(server("a"))
        val metadata = settings.getStringOrNull("servers.data")
        var readsFail = true
        var servicesStarted = 0
        val unavailable =
            object : SecureStore by secrets {
                override fun get(key: String): ByteArray? {
                    if (readsFail) throw SecureStoreException("temporarily unavailable")
                    return secrets.get(key)
                }
            }
        val application =
            koinApplication {
                modules(module { single { ServerRegistry(settings, unavailable) } })
            }
        try {
            val startup =
                ServerSessionStartup(
                    restore = { application.koin.get<ServerRegistry>() },
                    startServices = {
                        val restored = application.koin.get<ServerRegistry>()
                        assertEquals("a", restored.defaultServer?.id)
                        servicesStarted++
                    },
                )
            assertFalse(startup.start())
            assertFalse(startup.ready)
            assertEquals(0, servicesStarted)
            assertEquals(metadata, settings.getStringOrNull("servers.data"))

            readsFail = false
            assertTrue(startup.start())
            assertTrue(startup.ready)
            assertTrue(startup.start())
            assertEquals(1, servicesStarted)
        } finally {
            application.close()
        }
    }

    @Test
    fun unexpected_store_read_errors_are_preserved_as_retryable_failures() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        ServerRegistry(settings, secrets).addOrUpdate(server("a"))
        val keys = secrets.storedKeys()
        val unavailable =
            object : SecureStore by secrets {
                override fun get(key: String): ByteArray? = throw IllegalStateException("binder unavailable")
            }
        assertFailsWith<ServerSessionRestoreException> { ServerRegistry(settings, unavailable) }
        assertEquals(keys, secrets.storedKeys())
        assertEquals("a", ServerRegistry(settings, secrets).defaultServer?.id)
    }

    @Test
    fun unrelated_startup_errors_are_not_hidden_by_the_recovery_screen() {
        val startup = ServerSessionStartup(restore = { error("configuration error") }, startServices = {})
        assertFailsWith<IllegalStateException> { startup.start() }
        assertFalse(startup.ready)
    }
}
