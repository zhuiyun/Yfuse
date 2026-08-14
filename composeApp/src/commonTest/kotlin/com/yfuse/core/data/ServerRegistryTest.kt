package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServerRoute
import com.yfuse.core.model.ServersData
import com.yfuse.core.security.ServerMigrationCrypto
import com.yfuse.core.security.TestSecureStore
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRegistryTest {
    private fun server(
        id: String,
        token: String = "tok-$id",
    ) = SavedServer(id, "https://$id", "name-$id", "u", "user", token)

    private fun registry(
        settings: Settings = MapSettings(),
        secrets: TestSecureStore = TestSecureStore(),
    ) = ServerRegistry(settings, secrets)

    @Test
    fun firstAddedBecomesDefaultAndRemovingItReassignsDefault() {
        val registry = registry()
        registry.addOrUpdate(server("a"))
        registry.addOrUpdate(server("b"))
        assertEquals("a", registry.defaultServer?.id)

        registry.remove("a")

        assertEquals("b", registry.defaultServer?.id)
        registry.remove("b")
        assertNull(registry.defaultServer)
    }

    @Test
    fun tokenIsAbsentFromOrdinarySettingsAndReloadsFromSecureStore() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val token = "very-sensitive-bearer-token"
        registry(settings, secrets).addOrUpdate(server("a", token))

        val persisted = requireNotNull(settings.getStringOrNull("servers.data"))
        assertFalse(token in persisted)
        assertFalse("accessToken" in persisted)
        assertFalse("\"t\"" in persisted)
        assertEquals(1, secrets.storedKeys().size)

        val reloaded = registry(settings, secrets)
        assertEquals(token, reloaded.defaultServer?.accessToken)
        assertEquals("name-a", reloaded.defaultServer?.serverName)
    }

    @Test
    fun legacyPlaintextRegistryMigratesOnceAndIsImmediatelySanitized() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val saved = server("legacy", "legacy-plaintext-token")
        settings.putString(
            "servers.data",
            Json.encodeToString(
                ServersData.serializer(),
                ServersData(listOf(saved), saved.id),
            ),
        )

        val migrated = registry(settings, secrets)

        assertEquals(saved.accessToken, migrated.defaultServer?.accessToken)
        val persisted = requireNotNull(settings.getStringOrNull("servers.data"))
        assertFalse(saved.accessToken in persisted)
        assertFalse("accessToken" in persisted)
        assertEquals(1, secrets.storedKeys().size)
        assertEquals(saved.accessToken, registry(settings, secrets).defaultServer?.accessToken)
    }

    @Test
    fun failedLegacySecretMigrationPurgesPlaintextAndRequiresLogin() {
        val settings = MapSettings()
        val saved = server("legacy", "must-not-remain")
        settings.putString(
            "servers.data",
            Json.encodeToString(ServersData.serializer(), ServersData(listOf(saved), saved.id)),
        )
        val secrets = TestSecureStore().apply { failWrites = true }

        val loaded = registry(settings, secrets)

        assertTrue(
            loaded.data.value.servers
                .isEmpty(),
        )
        assertFalse("must-not-remain" in settings.getStringOrNull("servers.data").orEmpty())
    }

    @Test
    fun missingOrCorruptedSecureSecretFailsClosedAndRequiresLogin() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        registry(settings, secrets).addOrUpdate(server("a"))
        secrets.clear()

        assertTrue(
            registry(settings, secrets)
                .data.value.servers
                .isEmpty(),
        )

        registry(settings, secrets).addOrUpdate(server("b"))
        secrets.corruptedKeys += secrets.storedKeys().single()
        assertTrue(
            registry(settings, secrets)
                .data.value.servers
                .isEmpty(),
        )
    }

    @Test
    fun renamePersistsWithoutChangingIdentitySessionOrDefault() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val original = server("a")
        val registry = registry(settings, secrets)
        registry.addOrUpdate(original)

        assertTrue(registry.rename(original.id, "  客厅影院  "))

        val renamed = registry(settings, secrets).defaultServer
        assertEquals(original.id, renamed?.id)
        assertEquals("客厅影院", renamed?.serverName)
        assertEquals(original.accessToken, renamed?.accessToken)
    }

    @Test
    fun replacingConnectionKeepsBoundedPersistedAliasesAndReusesOneSecretSlot() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val registry = registry(settings, secrets)
        val ids = (0..MAX_SERVER_PREVIOUS_IDS + 2).map { "server-$it" }
        registry.addOrUpdate(server(ids.first()))

        ids.drop(1).forEach { nextId ->
            assertTrue(registry.replace(requireNotNull(registry.defaultServer).id, server(nextId)))
        }

        val reloaded = registry(settings, secrets)
        val latest = requireNotNull(reloaded.defaultServer)
        val expected = ids.dropLast(1).takeLast(MAX_SERVER_PREVIOUS_IDS)
        assertEquals(expected, latest.previousIds.toList())
        expected.forEach { alias -> assertEquals(latest.id, reloaded.serverById(alias)?.id) }
        assertEquals(1, secrets.storedKeys().size)
    }

    @Test
    fun removingServerRemovesItsEncryptedSecret() {
        val secrets = TestSecureStore()
        val registry = registry(secrets = secrets)
        registry.addOrUpdate(server("a"))
        assertEquals(1, secrets.storedKeys().size)

        registry.remove("a")

        assertTrue(secrets.storedKeys().isEmpty())
    }

    @Test
    fun routeMutationsAllowPublicAndLocalHttpWithoutConfirmation() {
        val registry = registry()
        val original =
            SavedServer(
                id = "secure",
                baseUrl = "https://media.example.com",
                serverName = "Emby",
                userId = "u",
                userName = "User",
                accessToken = "token",
            )
        registry.addOrUpdate(original)

        assertTrue(
            registry.setRoutes(
                original.id,
                listOf(
                    ServerRoute(ServerRoute.PRIMARY_ID, "主线路", original.baseUrl),
                    ServerRoute("r2", "公网 HTTP", "http://media.example.com:8096"),
                ),
            ),
        )
        assertTrue(
            registry.setRoutes(
                original.id,
                listOf(
                    ServerRoute(ServerRoute.PRIMARY_ID, "主线路", original.baseUrl),
                    ServerRoute("r2", "家庭 HTTP", "http://192.168.1.8:8096"),
                ),
            ),
        )
    }

    @Test
    fun failedOldCacheCleanupDoesNotFailCommittedReplacement() {
        val original = server("old")
        val replacement = server("new")
        val cacheKey = "library.cache.${original.id}"
        val backing = MapSettings().apply { putString(cacheKey, "cached-home") }
        val settings =
            object : Settings by backing {
                override fun remove(key: String) {
                    if (key == cacheKey) error("cache storage unavailable")
                    backing.remove(key)
                }
            }
        val registry = registry(settings).apply { addOrUpdate(original) }

        assertTrue(registry.replace(original.id, replacement))
        assertEquals(replacement.id, registry.defaultServer?.id)
        assertEquals("cached-home", backing.getStringOrNull(cacheKey))
    }

    @Test
    fun protectedBackupRoundTripsCredentialsAndRejectsWrongPasswordExpiryAndV1() {
        val source = registry()
        val first =
            SavedServer(
                SavedServer.idOf("https://one.example", "u1"),
                "https://one.example",
                "客厅影院",
                "u1",
                "Alice",
                "secret-one",
            )
        val second =
            SavedServer(
                SavedServer.idOf("https://two.example", "u2"),
                "https://two.example",
                "Two",
                "u2",
                "Bob",
                "secret-two",
            )
        source.addOrUpdate(first)
        source.addOrUpdate(second)
        source.setDefault(second.id)
        val password = "correct horse battery staple".toCharArray()
        val createdAt = 2_000_000_000L
        val payload = source.exportProtectedBackup(password, createdAt).getOrThrow()
        assertFalse("secret-one" in payload)
        assertFalse("secret-two" in payload)

        val target = registry()
        assertEquals(
            2,
            target.importProtectedBackup(payload, password, createdAt + 1).getOrThrow(),
        )
        assertEquals(second.id, target.defaultServer?.id)
        assertEquals("secret-one", target.serverById(first.id)?.accessToken)
        assertEquals("客厅影院", target.serverById(first.id)?.serverName)
        assertTrue(
            registry()
                .importProtectedBackup(
                    payload,
                    "incorrect password value".toCharArray(),
                    createdAt + 1,
                ).isFailure,
        )
        assertTrue(
            registry()
                .importProtectedBackup(
                    payload,
                    password,
                    createdAt + ServerMigrationCrypto.DEFAULT_TTL_SECONDS + 1,
                ).isFailure,
        )
        val v1 =
            """{"v":1,"s":[{"b":"https://old.example","u":"u","a":"User","t":"tok"}]}"""
        assertTrue(registry().importProtectedBackup(v1, password, createdAt + 1).isFailure)
        password.fill('\u0000')
    }

    @Test
    fun localCleartextConfirmationPersistsLocallyButIsNotCloudSerializable() {
        val settings = MapSettings()
        val secrets = TestSecureStore()
        val local =
            SavedServer(
                id = SavedServer.idOf("http://192.168.1.8:8096", "u"),
                baseUrl = "http://192.168.1.8:8096",
                serverName = "Home",
                userId = "u",
                userName = "User",
                accessToken = "token",
                localCleartextConfirmed = true,
            )

        registry(settings, secrets).addOrUpdate(local)

        assertTrue(registry(settings, secrets).defaultServer?.localCleartextConfirmed == true)
        assertTrue("\"lc\":true" in settings.getStringOrNull("servers.data").orEmpty())
        val cloudJson =
            Json.encodeToString(
                ServersData.serializer(),
                ServersData(listOf(local), local.id),
            )
        assertFalse("localCleartextConfirmed" in cloudJson)
    }

    @Test
    fun publicHttpCanBeSavedWithoutConfirmation() {
        val cleartext =
            SavedServer(
                id = SavedServer.idOf("http://media.example.com", "u"),
                baseUrl = "http://media.example.com",
                serverName = "Public",
                userId = "u",
                userName = "User",
                accessToken = "token",
            )
        val registry = registry()
        registry.addOrUpdate(cleartext)

        assertEquals("http://media.example.com", registry.defaultServer?.baseUrl)
    }

    @Test
    fun protectedBackupCanRestoreHttpServerWithoutConsent() {
        val http =
            SavedServer(
                id = SavedServer.idOf("http://192.168.1.8:8096", "u"),
                baseUrl = "http://192.168.1.8:8096",
                serverName = "Home",
                userId = "u",
                userName = "User",
                accessToken = "token",
            )
        val source = registry().apply { addOrUpdate(http) }
        val passphrase = "correct horse battery staple".toCharArray()
        val now = 2_000_000_000L
        val payload = source.exportProtectedBackup(passphrase, now).getOrThrow()

        assertEquals(1, registry().importProtectedBackup(payload, passphrase, now + 1).getOrThrow())
        passphrase.fill('\u0000')
    }
}
