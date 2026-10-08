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
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.ServerSyncManager
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
    fun a_protocol1_account_is_upgraded_on_login_and_its_other_devices_follow() =
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
    ) {
        private val settings = MapSettings()
        val secrets = TestSecureStore()
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
                    "/api/v1/account/rekey" -> rekey(request, body)
                    "/api/v1/account" -> delete(request, body)
                    else -> error("Unexpected path $path")
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
