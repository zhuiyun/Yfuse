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
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.sync.ServerSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountRepositoryTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun encrypted_sync_hides_secrets_and_restores_them_on_a_new_device() =
        runTest {
            val auth = authResponse()
            var storedSync: SyncResponse? = null
            var rawSyncRequest = ""
            val api =
                AccountApi(
                    createAccountClient(
                        MockEngine { request ->
                            when (request.url.encodedPath) {
                                "/api/v1/auth/register", "/api/v1/auth/login" ->
                                    respondJson(json.encodeToString(auth))
                                "/api/v1/account/password" -> {
                                    val change =
                                        json.decodeFromString<ChangePasswordRequest>(
                                            request.body.toByteArray().decodeToString(),
                                        )
                                    val current = assertNotNull(storedSync)
                                    assertEquals(current.version, change.expectedSyncVersion)
                                    val payload = assertNotNull(current.payload)
                                    storedSync =
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
                                    respondJson(json.encodeToString(auth))
                                }
                                "/api/v1/account/sync" ->
                                    when (request.method.value) {
                                        "GET" ->
                                            respondJson(
                                                json.encodeToString(storedSync ?: SyncResponse(version = 0)),
                                            )
                                        "PUT" -> {
                                            rawSyncRequest = request.body.toByteArray().decodeToString()
                                            val put = json.decodeFromString<PutSyncRequest>(rawSyncRequest)
                                            SyncResponse(
                                                version = put.baseVersion + 1,
                                                payload = put.payload,
                                                updatedAtEpochMs = 1_700_000_000_000,
                                            ).also { storedSync = it }
                                                .let { respondJson(json.encodeToString(it)) }
                                        }
                                        else -> error("Unexpected method ${request.method}")
                                    }
                                else -> error("Unexpected path ${request.url.encodedPath}")
                            }
                        },
                    ),
                )

            val first = testDependencies()
            val embyUrl = "https://emby.internal.example"
            val embyToken = "emby-secret-token-123"
            first.registry.addOrUpdate(
                SavedServer(
                    id = SavedServer.idOf(embyUrl, "user-1"),
                    baseUrl = embyUrl,
                    serverName = "家庭媒体库",
                    userId = "user-1",
                    userName = "viewer",
                    accessToken = embyToken,
                ),
            )
            val danmakuUrl = "https://danmaku.example/api/v2?token=danmaku-secret"
            assertNotNull(first.danmaku.addSource("私有弹幕源", danmakuUrl))
            val customUserAgent = "Yfuse-Private-Device/2.0"
            first.userAgent.setUserAgent(customUserAgent)
            val firstRepository = first.repository(api)

            assertTrue(
                firstRepository
                    .register(
                        username = "viewer_01",
                        password = "correct horse battery".toCharArray(),
                        nickname = "影友",
                        avatarId = 3,
                    ).isSuccess,
            )
            assertEquals(null, storedSync)
            assertTrue(firstRepository.uploadNow().isSuccess)

            assertNotNull(storedSync)
            assertFalse(rawSyncRequest.contains(embyToken))
            assertFalse(rawSyncRequest.contains(danmakuUrl))
            assertFalse(rawSyncRequest.contains(customUserAgent))
            assertFalse(rawSyncRequest.contains("correct horse battery"))
            assertFalse(rawSyncRequest.contains("家庭媒体库"))

            val second = testDependencies()
            val secondRepository = second.repository(api)
            assertTrue(
                secondRepository
                    .login(
                        username = "viewer_01",
                        password = "correct horse battery".toCharArray(),
                    ).isSuccess,
            )
            assertEquals(null, second.registry.defaultServer)
            assertTrue(
                second.danmaku.sources.value
                    .isEmpty(),
            )
            assertEquals("", second.userAgent.customValue.value)
            assertTrue(secondRepository.downloadNow().isSuccess)

            assertEquals(embyToken, second.registry.defaultServer?.accessToken)
            assertEquals(embyUrl, second.registry.defaultServer?.baseUrl)
            assertEquals(
                danmakuUrl,
                second.danmaku.sources.value
                    .single()
                    .url,
            )
            assertEquals(customUserAgent, second.userAgent.customValue.value)
            val signedIn = assertIs<AccountState.SignedIn>(secondRepository.state.value)
            assertEquals(1, signedIn.syncVersion)

            // Recreate the cloud after a clear with a different vault key but the same revision.
            // An old device changing the password must rewrap the latest cloud key, not its stale key.
            storedSync = null
            val replacement = testDependencies()
            val replacementRepository = replacement.repository(api)
            assertTrue(
                replacementRepository
                    .login(
                        username = "viewer_01",
                        password = "correct horse battery".toCharArray(),
                    ).isSuccess,
            )
            val replacementToken = "replacement-emby-token"
            replacement.registry.addOrUpdate(
                SavedServer(
                    id = SavedServer.idOf("https://replacement.example", "user-2"),
                    baseUrl = "https://replacement.example",
                    serverName = "新媒体库",
                    userId = "user-2",
                    userName = "viewer2",
                    accessToken = replacementToken,
                ),
            )
            assertTrue(replacementRepository.uploadNow().isSuccess)

            assertTrue(
                secondRepository
                    .changePassword(
                        currentPassword = "correct horse battery".toCharArray(),
                        newPassword = "new correct horse battery".toCharArray(),
                    ).isSuccess,
            )
            val fourth = testDependencies()
            val fourthRepository = fourth.repository(api)
            assertTrue(
                fourthRepository
                    .login(
                        username = "viewer_01",
                        password = "new correct horse battery".toCharArray(),
                    ).isSuccess,
            )
            assertEquals(null, fourth.registry.defaultServer)
            assertTrue(fourthRepository.downloadNow().isSuccess)
            assertEquals(replacementToken, fourth.registry.defaultServer?.accessToken)
        }

    private fun authResponse() =
        AuthResponse(
            user =
                AccountUser(
                    id = "account-user-id",
                    username = "viewer_01",
                    nickname = "影友",
                    avatarId = 3,
                    createdAtEpochMs = 1_700_000_000_000,
                    updatedAtEpochMs = 1_700_000_000_000,
                ),
            accessToken = "access-token",
            accessExpiresAtEpochMs = 9_000_000_000_000,
            refreshToken = "refresh-token",
            refreshExpiresAtEpochMs = 9_000_000_000_000,
        )

    private data class TestDependencies(
        val settings: MapSettings,
        val registry: ServerRegistry,
        val theme: ThemePreferences,
        val userAgent: UserAgentPreferences,
        val watch: WatchTogetherPreferences,
        val danmaku: DanmakuPreferences,
        val skip: SkipSegmentPreferences,
        val serverSync: ServerSyncManager,
        val secrets: MemorySecureStore,
    ) {
        fun repository(api: AccountApi): AccountRepository =
            AccountRepository(
                api = api,
                secureStore = secrets,
                crypto = VaultCrypto(),
                registry = registry,
                theme = theme,
                userAgent = userAgent,
                watch = watch,
                danmaku = danmaku,
                skip = skip,
                serverSync = serverSync,
                nowEpochMs = { 1_700_000_000_000 },
            )
    }

    private fun testDependencies(): TestDependencies {
        val settings = MapSettings()
        val registry = ServerRegistry(settings, TestSecureStore())
        return TestDependencies(
            settings = settings,
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
            secrets = MemorySecureStore(),
        )
    }

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}

private class MemorySecureStore : SecureStore {
    private val values = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(
        key: String,
        value: ByteArray,
    ) {
        values[key] = value.copyOf()
    }

    override fun remove(key: String): Boolean = values.remove(key) != null

    override fun clear() {
        values.values.forEach { it.fill(0) }
        values.clear()
    }
}
