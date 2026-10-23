package com.yfuse.core.account

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.model.SavedServer
import com.yfuse.core.security.AesGcmPayload
import com.yfuse.core.security.RecoveryKeyEnvelope
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.playback.EncryptedPlaybackEntity
import com.yfuse.core.sync.playback.PlaybackDeltaResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Account protocol 2 as the app speaks it, against an in-memory account service. */
class AccountProtocolV2ClientTest {
    @Test
    fun password_change_migrates_all_1024_cloud_only_records_and_preserves_unknown_fields() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service) // No PlaybackSyncStore or playback-sync preferences are installed.
            phone.repository.register("viewer_01", PASSWORD.toCharArray()).getOrThrow()
            val expected = seedPlayback(service, phone, 1_024)
            val originalKey = phone.secrets.get("vault_key")
            service.beforeRekey = { assertContentEquals(originalKey, phone.secrets.get("vault_key")) }

            phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()).getOrThrow()

            assertEquals(expected, readPlayback(service, phone))
            assertEquals(1_024, service.playback.size)
            assertTrue(service.playback.all { it.keyVersion == 2 })
            assertTrue(service.migrationBatches.count { it.items.isNotEmpty() } > 16)
            assertTrue(service.migrationBatches.all { it.items.size <= 64 })
            assertFalse(originalKey.contentEquals(phone.secrets.get("vault_key")))
        }

    @Test
    fun a_concurrent_cloud_write_restarts_the_migration_and_a_failed_commit_keeps_the_old_key() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            phone.repository.register("viewer_01", PASSWORD.toCharArray()).getOrThrow()
            phone.addServer("unchanged-sync")
            phone.repository.uploadNow().getOrThrow()
            val expected = seedPlayback(service, phone, 2)
            val oldKey = phone.secrets.get("vault_key")
            val oldSync = service.sync("viewer_01")
            service.failRekey = true
            assertTrue(phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()).isFailure)
            assertContentEquals(oldKey, phone.secrets.get("vault_key"))
            assertEquals(oldSync, service.sync("viewer_01"))
            assertEquals(expected, readPlayback(service, phone))
            assertTrue(service.staged.isEmpty(), "Failed staging is discarded")

            service.failRekey = false
            service.beforeRekey = {
                service.playback[0] = service.playback[0].copy(cursor = ++service.playbackCursor)
            }
            phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()).getOrThrow()
            assertEquals(expected, readPlayback(service, phone))
            assertEquals(3, service.migrationBatches.count { it.items.isEmpty() })
            assertEquals(2, service.user("viewer_01").vault?.keyVersion)
        }

    @Test
    fun logout_never_rewrites_protocol_memory_even_when_all_secure_store_writes_fail() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            phone.repository.register("viewer_01", PASSWORD.toCharArray()).getOrThrow()
            val remembered = phone.secrets.get("account_protocol2_names")
            phone.secrets.failWrites = true
            phone.repository.logout().getOrThrow()
            assertEquals(setOf("account_protocol2_names"), phone.secrets.storedKeys())
            assertContentEquals(remembered, phone.secrets.get("account_protocol2_names"))
            val restarted = Device(service, phone.secrets)
            service.claimsPasswordFor += "viewer_01"
            service.paths.clear()
            assertTrue(restarted.repository.login("viewer_01", PASSWORD.toCharArray()).isFailure)
            assertEquals(listOf("/api/v1/auth/prelogin"), service.paths)
            assertFalse(service.bodies.any { PASSWORD in it })
        }

    @Test
    fun an_old_service_cannot_make_the_client_skip_playback_migration() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            phone.repository.register("viewer_01", PASSWORD.toCharArray()).getOrThrow()
            val original = phone.secrets.get("vault_key")
            service.migrationAvailable = false
            val result = phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray())
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("升级服务端"),
            )
            assertFalse("/api/v1/account/rekey" in service.paths)
            assertContentEquals(original, phone.secrets.get("vault_key"))
        }

    @Test
    fun a_legacy_export_still_contains_its_offline_password_recovery_envelope() =
        runTest {
            val service = FakeAccountService(knowsProtocol2 = false)
            val phone = Device(service)
            phone.repository.register("viewer_01", PASSWORD.toCharArray()).getOrThrow()
            phone.addServer("legacy-offline-token")
            phone.repository.uploadNow().getOrThrow()
            val saved = phone.repository.exportAccount().getOrThrow()
            phone.repository.deleteAccount(PASSWORD.toCharArray()).getOrThrow()
            val exported = Json.decodeFromString<AccountExport>(saved)
            assertEquals(ACCOUNT_PROTOCOL_PASSWORD, exported.authProtocol)
            val payload = assertNotNull(exported.encryptedSync.payload)
            val crypto = VaultCrypto()
            val recovery =
                RecoveryKeyEnvelope(
                    version = assertNotNull(payload.wrapVersion),
                    salt = assertNotNull(payload.wrapSalt).base64UrlToBytes(),
                    iterations = assertNotNull(payload.wrapIterations),
                    wrappedKey =
                        AesGcmPayload(
                            assertNotNull(payload.wrapNonce).base64UrlToBytes(),
                            assertNotNull(payload.wrappedVaultKey).base64UrlToBytes(),
                        ),
                )
            val key =
                crypto.unwrapVaultKey(
                    recovery,
                    PASSWORD.toCharArray(),
                    (
                        "yfuse-vault-key:v1:${exported.user.id}:${payload.keyVersion}:" +
                            "${payload.wrapVersion}:${payload.wrapKdf}:${payload.wrapIterations}"
                    ).encodeToByteArray(),
                )
            val clear =
                crypto.decrypt(
                    key,
                    AesGcmPayload(payload.nonce.base64UrlToBytes(), payload.ciphertext.base64UrlToBytes()),
                    "yfuse-sync:v1:${exported.user.id}:${exported.encryptedSync.version}:${payload.keyVersion}"
                        .encodeToByteArray(),
                )
            try {
                assertTrue("legacy-offline-token" in decodeSyncDocument(clear))
            } finally {
                key.fill(0)
                clear.fill(0)
            }
        }

    private fun seedPlayback(
        service: FakeAccountService,
        device: Device,
        count: Int,
    ): Set<String> {
        val crypto = VaultCrypto()
        val key = assertNotNull(device.secrets.get("vault_key"))
        val userId = service.user("viewer_01").id
        return try {
            (0 until count).mapTo(linkedSetOf()) { index ->
                val mediaKey = "tmdb:movie:$index"
                val identity =
                    crypto
                        .hmacSha256(
                            key,
                            "yfuse-playback-entity-v1\u0000$mediaKey".encodeToByteArray(),
                        ).toBase64Url()
                val extra = if (index < 4) "x".repeat(90 * 1024) else "future-$index"
                val clear =
                    """{"schemaVersion":1,"state":{"mediaKey":"$mediaKey","deviceId":"remote"},""" +
                        """"future":"$extra"}"""
                val encrypted =
                    crypto.encrypt(
                        key,
                        clear.encodeToByteArray(),
                        "yfuse-playback:v1:$userId:$identity".encodeToByteArray(),
                    )
                service.playback +=
                    EncryptedPlaybackEntity(
                        identity,
                        "mutation-$index",
                        nonce = encrypted.nonce.toBase64Url(),
                        ciphertext = encrypted.ciphertext.toBase64Url(),
                        cursor = ++service.playbackCursor,
                    )
                clear
            }
        } finally {
            key.fill(0)
        }
    }

    private fun readPlayback(
        service: FakeAccountService,
        device: Device,
    ): Set<String> {
        val crypto = VaultCrypto()
        val key = assertNotNull(device.secrets.get("vault_key"))
        val userId = service.user("viewer_01").id
        return try {
            service.playback.mapTo(linkedSetOf()) { entity ->
                val clear =
                    crypto.decrypt(
                        key,
                        AesGcmPayload(entity.nonce.base64UrlToBytes(), entity.ciphertext.base64UrlToBytes()),
                        "yfuse-playback:v1:$userId:${entity.entityKey}".encodeToByteArray(),
                    )
                try {
                    clear.decodeToString()
                } finally {
                    clear.fill(0)
                }
            }
        } finally {
            key.fill(0)
        }
    }

    @Test
    fun an_export_keeps_every_parameter_needed_to_open_it_after_a_password_change() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            phone.addServer("offline-export-token")
            assertTrue(phone.repository.uploadNow().isSuccess)
            val saved = phone.repository.exportAccount().getOrThrow()
            assertTrue(phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()).isSuccess)

            // No service or device-keystore reads: the shared text and its original password suffice.
            val exported = Json.decodeFromString<AccountExport>(saved)
            assertEquals(ACCOUNT_PROTOCOL_DERIVED_KEY, exported.authProtocol)
            assertEquals(ACCOUNT_KDF, exported.kdf)
            val crypto = VaultCrypto()
            val keys =
                crypto.deriveAccountKeys(
                    PASSWORD.toCharArray(),
                    assertNotNull(exported.kdfSalt).base64UrlToBytes(),
                    assertNotNull(exported.kdfIterations),
                )
            val vault = assertNotNull(exported.vault)
            val key =
                crypto.unwrapAccountVaultKey(
                    AesGcmPayload(vault.nonce.base64UrlToBytes(), vault.wrappedKey.base64UrlToBytes()),
                    keys.wrapKey,
                    vault.keyVersion,
                )
            val payload = assertNotNull(exported.encryptedSync.payload)
            val plaintext =
                crypto.decrypt(
                    key,
                    AesGcmPayload(payload.nonce.base64UrlToBytes(), payload.ciphertext.base64UrlToBytes()),
                    "yfuse-sync:v1:${exported.user.id}:${exported.encryptedSync.version}:${payload.keyVersion}"
                        .encodeToByteArray(),
                )
            try {
                assertTrue("offline-export-token" in decodeSyncDocument(plaintext))
            } finally {
                keys.wipe()
                key.fill(0)
                plaintext.fill(0)
            }
        }

    @Test
    fun devices_share_one_vault_and_the_service_never_sees_the_password() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertEquals(ACCOUNT_PROTOCOL_DERIVED_KEY, service.user("viewer_01").protocol)
            assertEquals("2", phone.secrets.text("account_auth_protocol"))

            // An empty cloud used to make every new device generate a vault key of its own.
            val tablet = Device(service)
            assertTrue(tablet.repository.login("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertContentEquals(phone.secrets.get("vault_key"), tablet.secrets.get("vault_key"))

            phone.addServer("phone-emby-token")
            assertTrue(phone.repository.uploadNow().isSuccess)
            val stored = assertNotNull(service.sync("viewer_01").payload)
            assertEquals(1, stored.keyVersion)
            assertNull(stored.wrappedVaultKey, "Protocol 2 documents carry no password wrap")

            assertTrue(tablet.repository.downloadNow().isSuccess)
            assertEquals("phone-emby-token", tablet.registry.defaultServer?.accessToken)
            assertFalse(service.bodies.any { PASSWORD in it }, "The password never leaves the device")
        }

    @Test
    fun a_protocol1_account_upgrades_only_after_explicit_confirmation_and_its_other_devices_follow() =
        runTest {
            val service = FakeAccountService(knowsProtocol2 = false)
            val old = Device(service)
            assertTrue(old.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            old.addServer("old-emby-token")
            assertTrue(old.repository.uploadNow().isSuccess)
            assertEquals(ACCOUNT_PROTOCOL_PASSWORD, service.user("viewer_01").protocol)

            service.knowsProtocol2 = true
            val phone = Device(service)
            assertTrue(phone.repository.login("viewer_01", PASSWORD.toCharArray()).isSuccess)

            assertEquals(ACCOUNT_PROTOCOL_PASSWORD, service.user("viewer_01").protocol)
            assertEquals(1L, service.sync("viewer_01").version)
            assertTrue(assertIs<AccountState.SignedIn>(phone.repository.state.value).encryptionUpgradeAvailable)
            assertFalse("/api/v1/account/rekey" in service.paths)
            assertTrue(phone.repository.upgradeEncryption(PASSWORD.toCharArray()).isSuccess)

            val user = service.user("viewer_01")
            assertEquals(ACCOUNT_PROTOCOL_DERIVED_KEY, user.protocol)
            assertEquals(2, user.vault?.keyVersion)
            val upgraded = service.sync("viewer_01")
            assertEquals(2L, upgraded.version)
            assertEquals(2, upgraded.payload?.keyVersion)
            assertNull(upgraded.payload?.wrappedVaultKey)
            assertEquals("2", phone.secrets.text("vault_key_version"))
            val signedIn = assertIs<AccountState.SignedIn>(phone.repository.state.value)
            assertFalse(signedIn.encryptionUpgradeAvailable)

            // The service signed every other session out; the old key opens nothing new.
            assertTrue(old.repository.uploadNow().isFailure)
            assertIs<AccountState.SignedOut>(old.repository.state.value)

            val tv = Device(service)
            assertTrue(tv.repository.login("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertTrue(tv.repository.downloadNow().isSuccess)
            assertEquals("old-emby-token", tv.registry.defaultServer?.accessToken)
        }

    @Test
    fun ordinary_login_keeps_protocol1_sessions_and_encrypted_cloud_data_usable() =
        runTest {
            val service = FakeAccountService(knowsProtocol2 = false)
            val old = Device(service)
            assertTrue(old.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            old.addServer("legacy-token")
            assertTrue(old.repository.uploadNow().isSuccess)
            val previousKey = assertNotNull(old.secrets.get("vault_key"))
            val previousCloud = service.sync("viewer_01")

            service.knowsProtocol2 = true
            val phone = Device(service)
            assertTrue(phone.repository.login("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertEquals(ACCOUNT_PROTOCOL_PASSWORD, service.user("viewer_01").protocol)
            assertEquals(previousCloud, service.sync("viewer_01"))
            assertContentEquals(previousKey, phone.secrets.get("vault_key"))
            assertTrue(old.repository.uploadNow().isSuccess, "An ordinary login must keep the old device signed in")
            assertTrue(phone.repository.downloadNow().isSuccess)
            assertEquals("legacy-token", phone.registry.defaultServer?.accessToken)
            assertFalse("/api/v1/account/rekey" in service.paths)
        }

    @Test
    fun a_protocol1_password_change_keeps_old_client_compatibility() =
        runTest {
            val service = FakeAccountService(knowsProtocol2 = false)
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            phone.addServer("legacy-token")
            assertTrue(phone.repository.uploadNow().isSuccess)
            val previousKey = assertNotNull(phone.secrets.get("vault_key"))
            service.knowsProtocol2 = true

            assertTrue(phone.repository.changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray()).isSuccess)
            assertEquals(ACCOUNT_PROTOCOL_PASSWORD, service.user("viewer_01").protocol)
            assertContentEquals(previousKey, phone.secrets.get("vault_key"))
            assertTrue("/api/v1/account/password" in service.paths)
            assertFalse("/api/v1/account/rekey" in service.paths)
            val other = Device(service)
            assertTrue(other.repository.login("viewer_01", NEW_PASSWORD.toCharArray()).isSuccess)
            assertTrue(other.repository.downloadNow().isSuccess)
            assertEquals("legacy-token", other.registry.defaultServer?.accessToken)
        }

    @Test
    fun a_password_change_rotates_the_vault_key() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            phone.addServer("phone-emby-token")
            assertTrue(phone.repository.uploadNow().isSuccess)
            val firstKey = assertNotNull(phone.secrets.get("vault_key"))

            assertTrue(
                phone.repository
                    .changePassword(PASSWORD.toCharArray(), NEW_PASSWORD.toCharArray())
                    .isSuccess,
            )
            assertEquals(2, service.user("viewer_01").vault?.keyVersion)
            assertEquals(2, service.sync("viewer_01").payload?.keyVersion)
            assertFalse(firstKey.contentEquals(phone.secrets.get("vault_key")))

            val stale = Device(service)
            assertTrue(stale.repository.login("viewer_01", PASSWORD.toCharArray()).isFailure)
            val tv = Device(service)
            assertTrue(tv.repository.login("viewer_01", NEW_PASSWORD.toCharArray()).isSuccess)
            assertTrue(tv.repository.downloadNow().isSuccess)
            assertEquals("phone-emby-token", tv.registry.defaultServer?.accessToken)
            assertFalse(service.bodies.any { PASSWORD in it || NEW_PASSWORD in it })
        }

    @Test
    fun a_service_that_asks_an_upgraded_account_for_its_password_is_refused() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertTrue(phone.repository.logout().isSuccess)

            service.claimsPasswordFor += "viewer_01"
            val claimed = phone.repository.login("viewer_01", PASSWORD.toCharArray())
            assertTrue(claimed.isFailure)
            service.claimsPasswordFor.clear()
            service.knowsProtocol2 = false
            assertTrue(phone.repository.login("Viewer_01", PASSWORD.toCharArray()).isFailure)

            assertFalse(service.bodies.any { PASSWORD in it }, "Neither answer got the password")
            assertIs<AccountState.SignedOut>(phone.repository.state.value)
        }

    @Test
    fun an_unreadable_protocol_memory_never_authorizes_sending_the_password() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertTrue(phone.repository.logout().isSuccess)
            phone.secrets.corruptedKeys += "account_protocol2_names"
            service.claimsPasswordFor += "viewer_01"
            service.paths.clear()
            assertTrue(phone.repository.login("viewer_01", PASSWORD.toCharArray()).isFailure)
            assertEquals(listOf("/api/v1/auth/prelogin"), service.paths)
            assertFalse(service.bodies.any { PASSWORD in it })
        }

    @Test
    fun weak_key_parameters_from_the_service_are_refused_before_anything_is_sent() =
        runTest {
            val service = FakeAccountService(preloginIterations = 1_000)
            val phone = Device(service)

            assertTrue(phone.repository.login("viewer_01", PASSWORD.toCharArray()).isFailure)
            assertEquals(listOf("/api/v1/auth/prelogin"), service.paths)
        }

    @Test
    fun the_account_page_upgrades_a_signed_in_protocol1_account() =
        runTest {
            val service = FakeAccountService(knowsProtocol2 = false)
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)
            assertTrue(phone.repository.refreshEncryptionUpgradeOffer().isSuccess)
            assertFalse(assertIs<AccountState.SignedIn>(phone.repository.state.value).encryptionUpgradeAvailable)

            service.knowsProtocol2 = true
            assertTrue(phone.repository.refreshEncryptionUpgradeOffer().isSuccess)
            assertTrue(assertIs<AccountState.SignedIn>(phone.repository.state.value).encryptionUpgradeAvailable)

            assertTrue(phone.repository.upgradeEncryption(PASSWORD.toCharArray()).isSuccess)
            assertFalse(assertIs<AccountState.SignedIn>(phone.repository.state.value).encryptionUpgradeAvailable)
            assertEquals(ACCOUNT_PROTOCOL_DERIVED_KEY, service.user("viewer_01").protocol)
            assertEquals("2", phone.secrets.text("vault_key_version"))
        }

    @Test
    fun deleting_a_protocol2_account_proves_the_derived_key() =
        runTest {
            val service = FakeAccountService()
            val phone = Device(service)
            assertTrue(phone.repository.register("viewer_01", PASSWORD.toCharArray()).isSuccess)

            assertTrue(phone.repository.deleteAccount(PASSWORD.toCharArray()).isSuccess)
            assertNull(service.userOrNull("viewer_01"))
            assertIs<AccountState.SignedOut>(phone.repository.state.value)
            assertFalse(service.bodies.any { PASSWORD in it })
        }

    private class Device(
        service: FakeAccountService,
        val secrets: TestSecureStore = TestSecureStore(),
    ) {
        private val settings = MapSettings()
        val registry = ServerRegistry(settings, TestSecureStore())
        val repository =
            AccountRepository(
                api = AccountApi(createAccountClient(MockEngine { request -> service.handle(this, request) })),
                secureStore = secrets,
                crypto = VaultCrypto(),
                registry = registry,
                theme = ThemePreferences(settings),
                userAgent = UserAgentPreferences(settings),
                watch = WatchTogetherPreferences(settings),
                danmaku = DanmakuPreferences(settings),
                skip = SkipSegmentPreferences(settings),
                serverSync =
                    ServerSyncManager(
                        EmbyRepository(HttpClient(MockEngine { error("Unexpected Emby request") })),
                        registry,
                        settings,
                    ),
                nowEpochMs = { 1_700_000_000_000 },
            )

        fun addServer(token: String) {
            val url = "https://emby.example"
            registry.addOrUpdate(
                SavedServer(
                    id = SavedServer.idOf(url, "emby-user"),
                    baseUrl = url,
                    serverName = "家庭媒体库",
                    userId = "emby-user",
                    userName = "viewer",
                    accessToken = token,
                ),
            )
        }
    }

    private class FakeUser(
        val id: String,
        val username: String,
        var protocol: Int,
        var password: String? = null,
        var authKey: String? = null,
        var kdfSalt: String? = null,
        var kdfIterations: Int? = null,
        var vault: VaultEnvelope? = null,
    )

    /**
     * Enough of the account service for the client: prelogin, both registration and login shapes,
     * rekey, sync with its revision and key-version checks, refresh and deletion.
     */
    private class FakeAccountService(
        var knowsProtocol2: Boolean = true,
        private val preloginIterations: Int? = null,
    ) {
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        private val users = mutableMapOf<String, FakeUser>()
        private val syncByUser = mutableMapOf<String, SyncResponse>()
        private val accessTokens = mutableMapOf<String, String>()
        private val refreshTokens = mutableMapOf<String, String>()
        private var issued = 0
        val playback = mutableListOf<EncryptedPlaybackEntity>()
        var playbackCursor = 0L
        val staged = mutableListOf<PlaybackRekeyItem>()
        val migrationBatches = mutableListOf<PlaybackRekeyBatch>()
        var migrationId: String? = null
        var failRekey = false
        var migrationAvailable = true
        var beforeRekey: (() -> Unit)? = null
        val claimsPasswordFor = mutableSetOf<String>()
        val bodies = mutableListOf<String>()
        val paths = mutableListOf<String>()

        fun user(name: String): FakeUser = assertNotNull(userOrNull(name))

        fun userOrNull(name: String): FakeUser? = users[name.lowercase()]

        fun sync(name: String): SyncResponse = syncByUser[user(name).id] ?: SyncResponse(version = 0)

        suspend fun handle(
            scope: MockRequestHandleScope,
            request: HttpRequestData,
        ): HttpResponseData {
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            paths += path
            bodies += body
            return with(scope) {
                when (path) {
                    "/api/v1/auth/prelogin" -> prelogin(body)
                    "/api/v1/auth/register" -> register(body)
                    "/api/v1/auth/login" -> login(body)
                    "/api/v1/auth/refresh" -> refresh(body)
                    "/api/v1/auth/logout" -> respond("", HttpStatusCode.NoContent)
                    "/api/v1/account/sync" -> sync(request, body)
                    "/api/v1/account/export" -> export(request)
                    "/api/v1/account/rekey" -> rekey(request, body)
                    "/api/v1/account/playback/rekey" -> playbackRekey(request, body)
                    "/api/v1/account/password" -> changePassword(request, body)
                    "/api/v1/account" -> delete(request, body)
                    else ->
                        if (path.startsWith("/api/v1/account/playback/rekey/")) {
                            staged.clear()
                            migrationId = null
                            respond("", HttpStatusCode.NoContent)
                        } else {
                            error("Unexpected path $path")
                        }
                }
            }
        }

        private fun MockRequestHandleScope.prelogin(body: String): HttpResponseData {
            if (!knowsProtocol2) return respond("", HttpStatusCode.NotFound)
            val name = json.decodeFromString<PreloginRequest>(body).username.lowercase()
            val user = users[name]
            if (user?.protocol == ACCOUNT_PROTOCOL_PASSWORD || name in claimsPasswordFor) {
                return ok(PreloginResponse(authProtocol = ACCOUNT_PROTOCOL_PASSWORD))
            }
            return ok(
                PreloginResponse(
                    authProtocol = ACCOUNT_PROTOCOL_DERIVED_KEY,
                    kdf = ACCOUNT_KDF,
                    kdfIterations = preloginIterations ?: user?.kdfIterations ?: 600_000,
                    kdfSalt = user?.kdfSalt ?: ByteArray(16) { 7 }.toBase64Url(),
                ),
            )
        }

        private fun MockRequestHandleScope.register(body: String): HttpResponseData {
            val fields = Json.parseToJsonElement(body).jsonObject
            val user =
                if ("authKey" in fields) {
                    val request = json.decodeFromString<RegisterRequestV2>(body)
                    FakeUser(
                        id = "user-${users.size + 1}",
                        username = request.username,
                        protocol = ACCOUNT_PROTOCOL_DERIVED_KEY,
                        authKey = request.authKey,
                        kdfSalt = request.kdfSalt,
                        kdfIterations = request.kdfIterations,
                        vault = request.vault,
                    )
                } else {
                    val request = json.decodeFromString<RegisterRequest>(body)
                    FakeUser(
                        id = "user-${users.size + 1}",
                        username = request.username,
                        protocol = ACCOUNT_PROTOCOL_PASSWORD,
                        password = request.password,
                    )
                }
            if (user.username.lowercase() in users) return failure(HttpStatusCode.Conflict, "username_unavailable")
            users[user.username.lowercase()] = user
            return ok(auth(user), HttpStatusCode.Created)
        }

        private fun MockRequestHandleScope.login(body: String): HttpResponseData {
            val fields = Json.parseToJsonElement(body).jsonObject
            val name =
                fields["username"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .lowercase()
            val user = users[name] ?: return failure(HttpStatusCode.Unauthorized, "invalid_credentials")
            val proven =
                if ("authKey" in fields) {
                    user.protocol == ACCOUNT_PROTOCOL_DERIVED_KEY &&
                        json.decodeFromString<LoginRequestV2>(body).authKey == user.authKey
                } else {
                    if (user.protocol == ACCOUNT_PROTOCOL_DERIVED_KEY) {
                        return failure(HttpStatusCode.Forbidden, "client_upgrade_required")
                    }
                    json.decodeFromString<LoginRequest>(body).password == user.password
                }
            return if (proven) ok(auth(user)) else failure(HttpStatusCode.Unauthorized, "invalid_credentials")
        }

        private fun MockRequestHandleScope.refresh(body: String): HttpResponseData {
            val token = json.decodeFromString<RefreshRequest>(body).refreshToken
            val user =
                refreshTokens.remove(token)?.let { id -> users.values.first { it.id == id } }
                    ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            return ok(auth(user))
        }

        private fun MockRequestHandleScope.sync(
            request: HttpRequestData,
            body: String,
        ): HttpResponseData {
            val user = authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            val current = syncByUser[user.id] ?: SyncResponse(version = 0)
            return when (request.method.value) {
                "GET" -> ok(current)
                "DELETE" -> ok(SyncResponse(version = current.version + 1).also { syncByUser[user.id] = it })
                else -> {
                    val put = json.decodeFromString<PutSyncRequest>(body)
                    if (put.baseVersion != current.version) {
                        return failure(HttpStatusCode.Conflict, "sync_version_conflict")
                    }
                    val vault = user.vault
                    if (vault != null) {
                        if (put.payload.wrappedVaultKey != null) {
                            return failure(HttpStatusCode.BadRequest, "sync_key_wrap_forbidden")
                        }
                        if (put.payload.keyVersion != vault.keyVersion) {
                            return failure(HttpStatusCode.Conflict, "sync_key_version_conflict")
                        }
                    }
                    ok(SyncResponse(current.version + 1, put.payload, 1L).also { syncByUser[user.id] = it })
                }
            }
        }

        private fun MockRequestHandleScope.export(request: HttpRequestData): HttpResponseData {
            val user = authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            return ok(
                AccountExport(
                    schemaVersion = 1,
                    exportedAtEpochMs = 1L,
                    user = AccountUser(user.id, user.username, "影友", 0, 0L, 0L),
                    encryptedSync = syncByUser[user.id] ?: SyncResponse(version = 0),
                    vault = user.vault,
                    authProtocol = user.protocol,
                    kdf = ACCOUNT_KDF.takeIf { user.protocol == ACCOUNT_PROTOCOL_DERIVED_KEY },
                    kdfIterations = user.kdfIterations,
                    kdfSalt = user.kdfSalt,
                ),
            )
        }

        private fun MockRequestHandleScope.changePassword(
            request: HttpRequestData,
            body: String,
        ): HttpResponseData {
            val user = authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            val change = json.decodeFromString<ChangePasswordRequest>(body)
            if (user.protocol != ACCOUNT_PROTOCOL_PASSWORD || change.currentPassword != user.password) {
                return failure(HttpStatusCode.Forbidden, "current_password_invalid")
            }
            val current = syncByUser[user.id] ?: SyncResponse(version = 0)
            if (current.version != change.expectedSyncVersion) {
                return failure(HttpStatusCode.Conflict, "sync_version_conflict")
            }
            val payload = current.payload
            if (payload != null) {
                syncByUser[user.id] =
                    current.copy(
                        payload =
                            payload.copy(
                                keyVersion = change.keyVersion,
                                wrappedVaultKey = change.wrappedVaultKey,
                                wrapSalt = change.wrapSalt,
                                wrapNonce = change.wrapNonce,
                                wrapVersion = change.wrapVersion,
                                wrapKdf = change.wrapKdf,
                                wrapIterations = change.wrapIterations,
                            ),
                    )
            }
            user.password = change.newPassword
            accessTokens.values.removeAll { it == user.id }
            refreshTokens.values.removeAll { it == user.id }
            return ok(auth(user))
        }

        private fun MockRequestHandleScope.playbackRekey(
            request: HttpRequestData,
            body: String,
        ): HttpResponseData {
            if (!migrationAvailable) return respond("", HttpStatusCode.NotFound)
            authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            if (request.method.value == "GET") {
                val after = request.url.parameters["after"]?.toLong() ?: 0L
                val rows = playback.filter { it.cursor > after }.sortedBy { it.cursor }
                val page = mutableListOf<EncryptedPlaybackEntity>()
                var bytes = 0
                for (row in rows) {
                    if (page.size == 200 || (page.isNotEmpty() && bytes + row.ciphertext.length > 192 * 1024)) break
                    page += row
                    bytes += row.ciphertext.length
                }
                val more = page.size < rows.size
                return ok(PlaybackDeltaResponse(if (more) page.last().cursor else playbackCursor, page, more))
            }
            assertTrue(body.encodeToByteArray().size <= 382_294)
            val batch = json.decodeFromString<PlaybackRekeyBatch>(body)
            assertTrue(batch.items.size <= 64)
            migrationBatches += batch
            if (batch.items.isEmpty()) {
                migrationId = batch.migrationId
                staged.clear()
            } else {
                assertEquals(migrationId, batch.migrationId)
                staged += batch.items
            }
            return ok(PlaybackRekeyReceipt(batch.migrationId))
        }

        private fun MockRequestHandleScope.rekey(
            request: HttpRequestData,
            body: String,
        ): HttpResponseData {
            val user = authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            val rekey = json.decodeFromString<RekeyRequest>(body)
            val proven =
                when (user.protocol) {
                    ACCOUNT_PROTOCOL_DERIVED_KEY -> rekey.currentAuthKey == user.authKey
                    else -> rekey.currentPassword == user.password
                }
            if (!proven) return failure(HttpStatusCode.Forbidden, "current_password_invalid")
            if (failRekey) return failure(HttpStatusCode.ServiceUnavailable, "unavailable")
            beforeRekey?.also { beforeRekey = null }?.invoke()
            if (rekey.playbackMigrationId != migrationId ||
                rekey.playbackCursor != playbackCursor ||
                staged.size != playback.size ||
                staged.any { item ->
                    playback.none {
                        it.entityKey == item.sourceEntityKey &&
                            it.cursor == item.sourceCursor
                    }
                }
            ) {
                return failure(HttpStatusCode.Conflict, "playback_version_conflict")
            }
            val current = syncByUser[user.id] ?: SyncResponse(version = 0)
            val currentKeyVersion = user.vault?.keyVersion ?: maxOf(current.payload?.keyVersion ?: 0, 1)
            if (rekey.vault.keyVersion <= currentKeyVersion) {
                return failure(HttpStatusCode.Conflict, "vault_key_version_conflict")
            }
            val sync = rekey.sync
            if (current.payload != null && sync == null) {
                return failure(HttpStatusCode.Conflict, "sync_reencryption_required")
            }
            if (sync != null) {
                if (sync.baseVersion != current.version) {
                    return failure(HttpStatusCode.Conflict, "sync_version_conflict")
                }
                syncByUser[user.id] = SyncResponse(current.version + 1, sync.payload, 1L)
            }
            user.protocol = ACCOUNT_PROTOCOL_DERIVED_KEY
            user.password = null
            user.authKey = rekey.authKey
            user.kdfSalt = rekey.kdfSalt
            user.kdfIterations = rekey.kdfIterations
            user.vault = rekey.vault
            val migrated = staged.map { it.entity.copy(cursor = ++playbackCursor) }
            playback.clear()
            playback += migrated
            staged.clear()
            migrationId = null
            accessTokens.values.removeAll { it == user.id }
            refreshTokens.values.removeAll { it == user.id }
            return ok(auth(user))
        }

        private fun MockRequestHandleScope.delete(
            request: HttpRequestData,
            body: String,
        ): HttpResponseData {
            val user = authorized(request) ?: return failure(HttpStatusCode.Unauthorized, "unauthorized")
            val fields = Json.parseToJsonElement(body).jsonObject
            val proven =
                when (user.protocol) {
                    ACCOUNT_PROTOCOL_DERIVED_KEY ->
                        "authKey" in fields &&
                            json.decodeFromString<DeleteAccountRequestV2>(body).authKey == user.authKey
                    else -> json.decodeFromString<DeleteAccountRequest>(body).password == user.password
                }
            if (!proven) return failure(HttpStatusCode.Forbidden, "current_password_invalid")
            users.remove(user.username.lowercase())
            syncByUser.remove(user.id)
            return respond("", HttpStatusCode.NoContent)
        }

        private fun authorized(request: HttpRequestData): FakeUser? {
            val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ") ?: return null
            val id = accessTokens[token] ?: return null
            return users.values.firstOrNull { it.id == id }
        }

        private fun auth(user: FakeUser): AuthResponse {
            issued++
            val access = "access-$issued"
            val refresh = "refresh-$issued"
            accessTokens[access] = user.id
            refreshTokens[refresh] = user.id
            return AuthResponse(
                user = AccountUser(user.id, user.username, "影友", 0, 0L, 0L),
                accessToken = access,
                accessExpiresAtEpochMs = 9_000_000_000_000,
                refreshToken = refresh,
                refreshExpiresAtEpochMs = 9_000_000_000_000,
                authProtocol = user.protocol,
                vault = user.vault,
            )
        }

        private inline fun <reified T> MockRequestHandleScope.ok(
            value: T,
            status: HttpStatusCode = HttpStatusCode.OK,
        ): HttpResponseData =
            respond(
                json.encodeToString(value),
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )

        private fun MockRequestHandleScope.failure(
            status: HttpStatusCode,
            code: String,
        ): HttpResponseData =
            respond(
                """{"error":{"code":"$code","message":"$code"}}""",
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
    }

    private companion object {
        const val PASSWORD = "correct horse battery"
        const val NEW_PASSWORD = "a brand new passphrase"
    }
}

private fun TestSecureStore.text(key: String): String? = get(key)?.decodeToString()
