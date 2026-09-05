package com.yfuse.core.account

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.security.CryptoPrimitives
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.SecureStoreCorruptedException
import com.yfuse.core.security.SecureStoreException
import com.yfuse.core.security.TestSecureStore
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.ServerSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccountRepositoryStateTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun change_password_replaces_every_locally_stored_wrapper_field() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val oldVaultKey = assertNotNull(secureStore.get(KEY_VAULT_KEY))
            val oldSalt = assertNotNull(secureStore.get(KEY_WRAP_SALT))
            val oldNonce = assertNotNull(secureStore.get(KEY_WRAP_NONCE))
            val oldWrappedKey = assertNotNull(secureStore.get(KEY_WRAPPED_VAULT))
            var capturedChange: ChangePasswordRequest? = null
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH ->
                                respondAccountJson(
                                    json.encodeToString(authResponse(refreshToken = "refresh-before-change")),
                                )
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            PASSWORD_PATH -> {
                                capturedChange =
                                    json.decodeFromString(
                                        request.body.toByteArray().decodeToString(),
                                    )
                                respondAccountJson(
                                    json.encodeToString(
                                        authResponse(
                                            accessToken = "access-after-change",
                                            refreshToken = "refresh-after-change",
                                        ),
                                    ),
                                )
                            }
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore)
            repository.start()
            awaitAccountState(repository) {
                it is AccountState.SignedIn && it.syncVersion == 5L
            }
            secureStore.resetObservations()

            val result =
                repository.changePassword(
                    currentPassword = "current password".toCharArray(),
                    newPassword = "replacement password".toCharArray(),
                )

            assertTrue(result.isSuccess)
            val request = assertNotNull(capturedChange)
            assertEquals(5L, request.expectedSyncVersion)
            assertEquals(1, request.keyVersion)
            assertContentEquals(
                request.wrappedVaultKey.base64UrlToBytes(),
                secureStore.get(KEY_WRAPPED_VAULT),
            )
            assertContentEquals(request.wrapSalt.base64UrlToBytes(), secureStore.get(KEY_WRAP_SALT))
            assertContentEquals(request.wrapNonce.base64UrlToBytes(), secureStore.get(KEY_WRAP_NONCE))
            assertEquals(request.wrapVersion.toString(), secureStore.text(KEY_WRAP_VERSION))
            assertEquals(request.wrapKdf, secureStore.text(KEY_WRAP_KDF))
            assertEquals(request.wrapIterations.toString(), secureStore.text(KEY_WRAP_ITERATIONS))
            assertContentEquals(oldVaultKey, secureStore.get(KEY_VAULT_KEY))

            val locallyWrittenVaultFields =
                secureStore.putKeys.filterTo(mutableSetOf()) {
                    it in ALL_LOCAL_VAULT_FIELDS
                }
            assertEquals(ALL_LOCAL_VAULT_FIELDS, locallyWrittenVaultFields)
            assertFalse(oldSalt.contentEquals(secureStore.get(KEY_WRAP_SALT)))
            assertFalse(oldNonce.contentEquals(secureStore.get(KEY_WRAP_NONCE)))
            assertFalse(oldWrappedKey.contentEquals(secureStore.get(KEY_WRAPPED_VAULT)))
            assertEquals("refresh-after-change", secureStore.text(KEY_REFRESH_TOKEN))
            val signedIn = assertIs<AccountState.SignedIn>(repository.state.value)
            assertEquals("access-after-change", signedIn.session.accessToken)
            assertEquals("登录密码已修改，加密密钥已同步更新", signedIn.message)
        }

    @Test
    fun upload_conflict_stops_after_one_put_and_restores_non_syncing_state() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val uploadRequests = mutableListOf<String>()
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> respondAccountJson(json.encodeToString(authResponse()))
                            SYNC_PATH ->
                                when (request.method.value) {
                                    "GET" -> {
                                        uploadRequests += "GET"
                                        respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                                    }
                                    "PUT" -> {
                                        uploadRequests += "PUT"
                                        respondAccountJson(
                                            body =
                                                json.encodeToString(
                                                    ErrorEnvelope(
                                                        ErrorBody(
                                                            code = "sync_version_conflict",
                                                            message = "Sync version conflict",
                                                            currentVersion = 6,
                                                        ),
                                                    ),
                                                ),
                                            status = HttpStatusCode.Conflict,
                                        )
                                    }
                                    else -> error("Unexpected method ${request.method}")
                                }
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore)
            repository.start()
            awaitAccountState(repository) {
                it is AccountState.SignedIn && it.syncVersion == 5L
            }
            uploadRequests.clear()

            val result = repository.uploadNow()

            val failure = assertIs<AccountApiException>(result.exceptionOrNull())
            assertEquals("sync_version_conflict", failure.code)
            assertEquals(6L, failure.currentVersion)
            assertEquals(listOf("GET", "PUT"), uploadRequests)
            val signedIn = assertIs<AccountState.SignedIn>(repository.state.value)
            assertEquals(5L, signedIn.syncVersion)
            assertFalse(signedIn.cloudHasData)
            assertFalse(signedIn.syncing)
            assertEquals("云端已有更新，请先从云端恢复", signedIn.message)
        }

    @Test
    fun sync_rebuilds_missing_local_key_wrap_from_the_cloud_copy() =
        runTest {
            val secureStore =
                RecordingAccountSecureStore().apply {
                    seedStoredSession()
                    // A vault whose wrap entries never landed. This is the state that made every sync
                    // report a missing sync key even though the server still holds the same wrap.
                    listOf(
                        KEY_WRAP_SALT,
                        KEY_WRAP_NONCE,
                        KEY_WRAPPED_VAULT,
                        KEY_WRAP_VERSION,
                        KEY_WRAP_KDF,
                        KEY_WRAP_ITERATIONS,
                    ).forEach { remove(it) }
                    resetObservations()
                }
            val cloudPayload = cloudSyncPayload()
            var uploaded: PutSyncRequest? = null
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> respondAccountJson(json.encodeToString(authResponse()))
                            SYNC_PATH ->
                                when (request.method.value) {
                                    "GET" ->
                                        respondAccountJson(
                                            json.encodeToString(SyncResponse(version = 5, payload = cloudPayload)),
                                        )
                                    "PUT" -> {
                                        uploaded =
                                            json.decodeFromString(
                                                request.body.toByteArray().decodeToString(),
                                            )
                                        respondAccountJson(
                                            json.encodeToString(
                                                SyncResponse(version = 6, payload = cloudPayload),
                                            ),
                                        )
                                    }
                                    else -> error("Unexpected method ${request.method}")
                                }
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore)

            repository.start()
            awaitAccountState(repository) { it is AccountState.SignedIn && it.syncVersion == 5L }

            assertContentEquals(
                cloudPayload.wrappedVaultKey?.base64UrlToBytes(),
                secureStore.get(KEY_WRAPPED_VAULT),
            )
            assertEquals(cloudPayload.wrapKdf, secureStore.text(KEY_WRAP_KDF))

            val result = repository.uploadNow()

            assertTrue(result.isSuccess)
            val request = assertNotNull(uploaded)
            assertEquals(cloudPayload.wrappedVaultKey, request.payload.wrappedVaultKey)
            assertEquals(cloudPayload.wrapSalt, request.payload.wrapSalt)
            assertEquals(cloudPayload.wrapNonce, request.payload.wrapNonce)
            val signedIn = assertIs<AccountState.SignedIn>(repository.state.value)
            assertEquals(6L, signedIn.syncVersion)
            assertEquals("已用本机数据覆盖云端", signedIn.message)
        }

    @Test
    fun restore_session_refresh_unauthorized_clears_credentials_and_signs_out() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val api =
                accountApi(
                    MockEngine { request ->
                        assertEquals(REFRESH_PATH, request.url.encodedPath)
                        respondAccountJson(
                            body =
                                json.encodeToString(
                                    ErrorEnvelope(ErrorBody("invalid_refresh_token", "Refresh token expired")),
                                ),
                            status = HttpStatusCode.Unauthorized,
                        )
                    },
                )
            val repository = accountRepository(api, secureStore)

            repository.start()
            awaitAccountState(repository) { it is AccountState.SignedOut }

            assertIs<AccountState.SignedOut>(repository.state.value)
            assertTrue(secureStore.snapshot().isEmpty())
            assertEquals(1, secureStore.clearCount)
        }

    @Test
    fun restore_session_transient_refresh_failure_preserves_credentials() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val originalSecrets = secureStore.snapshot()
            val api =
                accountApi(
                    MockEngine { request ->
                        assertEquals(REFRESH_PATH, request.url.encodedPath)
                        respondAccountJson(
                            body =
                                json.encodeToString(
                                    ErrorEnvelope(ErrorBody("account_busy", "Account service is busy")),
                                ),
                            status = HttpStatusCode.ServiceUnavailable,
                        )
                    },
                )
            val repository = accountRepository(api, secureStore)

            repository.start()
            val failed =
                assertIs<AccountState.RestoreFailed>(
                    awaitAccountState(repository) { it is AccountState.RestoreFailed },
                )

            assertEquals("账号服务暂时不可用（HTTP 503），本机登录信息仍已安全保留。", failed.message)
            assertEquals(originalSecrets, secureStore.snapshot().filterKeys { it != "pending_refresh" })
            assertEquals(0, secureStore.clearCount)
        }

    @Test
    fun restore_session_names_an_edge_failure_in_front_of_the_account_service() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val originalSecrets = secureStore.snapshot()
            val api =
                accountApi(
                    MockEngine { request ->
                        assertEquals(REFRESH_PATH, request.url.encodedPath)
                        // A Cloudflare tunnel that cannot reach the origin answers with its own
                        // HTML page, not the account service's JSON envelope.
                        respond(
                            content = "<html><title>Error 1033: Cloudflare Tunnel error</title></html>",
                            status = HttpStatusCode(530, "Origin Unreachable"),
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Html.toString()),
                        )
                    },
                )
            val repository = accountRepository(api, secureStore)

            repository.start()
            val failed =
                assertIs<AccountState.RestoreFailed>(
                    awaitAccountState(repository) { it is AccountState.RestoreFailed },
                )

            assertEquals("账号服务暂时不可用（HTTP 530），本机登录信息仍已安全保留。", failed.message)
            assertEquals(originalSecrets, secureStore.snapshot().filterKeys { it != "pending_refresh" })
            assertEquals(0, secureStore.clearCount)
        }

    @Test
    fun restore_failure_wording_separates_the_phone_the_network_and_the_service() {
        assertEquals(
            "本机登录信息暂时无法读取或保存，请重试。",
            restoreFailureMessage(SecureStoreException("keystore busy")),
        )
        assertEquals(
            "账号服务返回异常（HTTP 429），本机登录信息仍已安全保留。",
            restoreFailureMessage(
                AccountApiException("rate_limited", "slow down", HttpStatusCode.TooManyRequests),
            ),
        )
        assertEquals(
            "网络暂不可用，本机登录信息仍已安全保留。",
            restoreFailureMessage(IllegalStateException("connection reset")),
        )
        assertEquals(
            "http_429:rate_limited",
            restoreFailureReason(
                AccountApiException("rate_limited", "slow down", HttpStatusCode.TooManyRequests),
            ),
        )
        assertEquals("network:IllegalStateException", restoreFailureReason(IllegalStateException("x")))
    }

    @Test
    fun stalled_refresh_becomes_retryable_without_erasing_credentials() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val originalSecrets = secureStore.snapshot()
            val firstRefreshStarted = CompletableDeferred<Unit>()
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> {
                                if (firstRefreshStarted.complete(Unit)) awaitCancellation()
                                respondAccountJson(json.encodeToString(authResponse(refreshToken = "retried-refresh")))
                            }
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore, restoreRequestTimeoutMillis = 1_000)

            repository.start()
            val stalled =
                assertIs<AccountState.RestoreFailed>(
                    awaitAccountState(repository) { it is AccountState.RestoreFailed },
                )

            assertEquals("账号服务响应超时，本机登录信息仍已安全保留。", stalled.message)
            assertTrue(firstRefreshStarted.isCompleted)
            assertEquals(originalSecrets, secureStore.snapshot().filterKeys { it != "pending_refresh" })
            assertEquals(0, secureStore.clearCount)

            repository.retryRestore()
            val signedIn =
                assertIs<AccountState.SignedIn>(
                    awaitAccountState(repository) { it is AccountState.SignedIn && it.syncVersion == 5L },
                )
            assertEquals("restored-access", signedIn.session.accessToken)
            assertEquals("retried-refresh", secureStore.text(KEY_REFRESH_TOKEN))
        }

    @Test
    fun stalled_sync_keeps_the_rotated_session_and_releases_account_operations() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH ->
                                respondAccountJson(json.encodeToString(authResponse(refreshToken = "rotated-refresh")))
                            SYNC_PATH -> awaitCancellation()
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val tokenSource = AccountAccessTokenSource()
            val repository =
                accountRepository(api, secureStore, tokenSource, restoreRequestTimeoutMillis = 1_000)

            repository.start()
            val signedIn =
                assertIs<AccountState.SignedIn>(
                    awaitAccountState(repository) {
                        it is AccountState.SignedIn && it.message == "已登录，暂时无法读取云端数据"
                    },
                )
            assertEquals("restored-access", signedIn.session.accessToken)
            assertEquals("rotated-refresh", secureStore.text(KEY_REFRESH_TOKEN))
            assertNotNull(secureStore.get(KEY_VAULT_KEY))
            assertEquals(0, secureStore.clearCount)
            withContext(Dispatchers.Default) {
                withTimeout(5_000) {
                    assertEquals("restored-access", tokenSource.validAccessTokenFor(ACCOUNT_BASE_URL))
                }
            }
        }

    @Test
    fun restore_session_sync_failure_keeps_refreshed_session_signed_in() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH ->
                                respondAccountJson(
                                    json.encodeToString(
                                        authResponse(
                                            accessToken = "refreshed-access",
                                            refreshToken = "rotated-refresh",
                                        ),
                                    ),
                                )
                            SYNC_PATH ->
                                respondAccountJson(
                                    body =
                                        json.encodeToString(
                                            ErrorEnvelope(ErrorBody("account_busy", "Account service is busy")),
                                        ),
                                    status = HttpStatusCode.ServiceUnavailable,
                                )
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore)

            repository.start()
            val signedIn =
                assertIs<AccountState.SignedIn>(
                    awaitAccountState(repository) {
                        it is AccountState.SignedIn && it.message == "已登录，暂时无法读取云端数据"
                    },
                )

            assertEquals("refreshed-access", signedIn.session.accessToken)
            assertEquals(0L, signedIn.syncVersion)
            assertFalse(signedIn.syncing)
            assertEquals("rotated-refresh", secureStore.text(KEY_REFRESH_TOKEN))
            assertNotNull(secureStore.get(KEY_VAULT_KEY))
            assertEquals(0, secureStore.clearCount)
        }

    @Test
    fun forbidden_invite_issue_removes_server_capability_from_the_live_session() =
        runTest {
            val secureStore = RecordingAccountSecureStore().apply { seedStoredSession() }
            val tokenSource = AccountAccessTokenSource()
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH ->
                                respondAccountJson(
                                    json.encodeToString(
                                        authResponse(capabilities = listOf(INVITE_ISSUE_CAPABILITY)),
                                    ),
                                )
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 0)))
                            INVITES_PATH ->
                                respondAccountJson(
                                    json.encodeToString(ErrorEnvelope(ErrorBody("forbidden", "denied"))),
                                    HttpStatusCode.Forbidden,
                                )
                            else -> error("Unexpected path ${request.url.encodedPath}")
                        }
                    },
                )
            val repository = accountRepository(api, secureStore, tokenSource)
            repository.start()
            val restored =
                assertIs<AccountState.SignedIn>(
                    awaitAccountState(repository) {
                        it is AccountState.SignedIn && it.session.user.canIssueInvites()
                    },
                )
            assertTrue(restored.session.user.canIssueInvites())

            val result = repository.issueInvite()

            assertTrue(result.isFailure)
            val current = assertIs<AccountState.SignedIn>(repository.state.value)
            assertFalse(current.session.user.canIssueInvites())
        }

    private fun accountApi(engine: MockEngine): AccountApi = AccountApi(createAccountClient(engine))

    private fun authResponse(
        accessToken: String = "restored-access",
        refreshToken: String = "rotated-refresh-token",
        capabilities: List<String> = emptyList(),
    ) = AuthResponse(
        user =
            AccountUser(
                id = "account-user-id",
                username = "viewer_01",
                nickname = "影友",
                avatarId = 3,
                createdAtEpochMs = 1_700_000_000_000,
                updatedAtEpochMs = 1_700_000_000_000,
                capabilities = capabilities,
            ),
        accessToken = accessToken,
        accessExpiresAtEpochMs = 9_000_000_000_000,
        refreshToken = refreshToken,
        refreshExpiresAtEpochMs = 9_000_000_000_000,
    )

    private fun cloudSyncPayload() =
        EncryptedSyncPayload(
            nonce = ByteArray(VaultCrypto.GCM_NONCE_SIZE_BYTES) { (it + 5).toByte() }.toBase64Url(),
            ciphertext = ByteArray(64) { (it + 7).toByte() }.toBase64Url(),
            wrappedVaultKey = ByteArray(48) { (it + 11).toByte() }.toBase64Url(),
            wrapSalt = ByteArray(16) { (it + 13).toByte() }.toBase64Url(),
            wrapNonce = ByteArray(VaultCrypto.GCM_NONCE_SIZE_BYTES) { (it + 17).toByte() }.toBase64Url(),
            wrapVersion = 1,
            wrapKdf = "PBKDF2-HMAC-SHA256",
            wrapIterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS,
        )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondAccountJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    @Test
    fun temporary_credential_read_failure_is_retryable_without_clearing_secrets() =
        runTest {
            val backing = RecordingAccountSecureStore().apply { seedStoredSession() }
            val original = backing.snapshot()
            var fail = true
            val store =
                object : SecureStore by backing {
                    override fun get(key: String): ByteArray? {
                        if (fail) throw SecureStoreException("Keystore temporarily unavailable")
                        return backing.get(key)
                    }
                }
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> respondAccountJson(json.encodeToString(authResponse()))
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            else -> error("Unexpected request")
                        }
                    },
                )
            val repository = accountRepository(api, store)
            repository.start()
            awaitAccountState(repository) { it is AccountState.RestoreFailed }
            assertEquals(original, backing.snapshot())
            assertEquals(0, backing.clearCount)
            fail = false
            repository.retryRestore()
            awaitAccountState(repository) { it is AccountState.SignedIn && it.syncVersion == 5L }
        }

    @Test
    fun corrupted_sync_key_does_not_erase_a_valid_login() =
        runTest {
            val backing = RecordingAccountSecureStore().apply { seedStoredSession() }
            val store =
                object : SecureStore by backing {
                    override fun get(key: String): ByteArray? {
                        if (key == KEY_VAULT_KEY) throw SecureStoreCorruptedException()
                        return backing.get(key)
                    }
                }
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH ->
                                respondAccountJson(
                                    json.encodeToString(authResponse(refreshToken = "new-refresh")),
                                )
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            else -> error("Unexpected request")
                        }
                    },
                )
            val repository = accountRepository(api, store)
            repository.start()
            awaitAccountState(repository) { it is AccountState.SignedIn && it.message != null }
            assertEquals("new-refresh", backing.text(KEY_REFRESH_TOKEN))
            assertEquals(0, backing.clearCount)
        }

    @Test
    fun lost_refresh_response_reuses_persisted_request_after_repository_restart() =
        runTest {
            val store = RecordingAccountSecureStore().apply { seedStoredSession() }
            var issuedRequest: RefreshRequest? = null
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> {
                                val refresh =
                                    json.decodeFromString<RefreshRequest>(
                                        request.body.toByteArray().decodeToString(),
                                    )
                                val pending =
                                    json.decodeFromString<PendingAccountRefresh>(
                                        store.text("pending_refresh")!!,
                                    )
                                assertEquals(refresh.requestId, pending.requestId)
                                assertEquals(refresh.refreshToken, pending.refreshToken)
                                if (issuedRequest == null) {
                                    issuedRequest = refresh
                                    throw java.io.IOException("Response lost after server rotation")
                                }
                                assertEquals(issuedRequest, refresh)
                                respondAccountJson(
                                    json.encodeToString(authResponse(refreshToken = "recovered-refresh")),
                                )
                            }
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            else -> error("Unexpected request")
                        }
                    },
                )
            val first = accountRepository(api, store)
            first.start()
            awaitAccountState(first) { it is AccountState.RestoreFailed }
            val restarted = accountRepository(api, store)
            restarted.start()
            awaitAccountState(restarted) { it is AccountState.SignedIn && it.syncVersion == 5L }
            assertEquals("recovered-refresh", store.text(KEY_REFRESH_TOKEN))
            assertEquals(null, store.text("pending_refresh"))
            assertEquals(0, store.clearCount)
        }

    @Test
    fun failed_refresh_token_commit_preserves_pending_request_for_retry() =
        runTest {
            val backing = RecordingAccountSecureStore().apply { seedStoredSession() }
            var failWrite = true
            val store =
                object : SecureStore by backing {
                    override fun put(
                        key: String,
                        value: ByteArray,
                    ) {
                        if (key == KEY_REFRESH_TOKEN && failWrite) throw SecureStoreException("Disk unavailable")
                        backing.put(key, value)
                    }
                }
            val requests = mutableListOf<RefreshRequest>()
            val api =
                accountApi(
                    MockEngine { request ->
                        when (request.url.encodedPath) {
                            REFRESH_PATH -> {
                                requests +=
                                    json.decodeFromString<RefreshRequest>(request.body.toByteArray().decodeToString())
                                respondAccountJson(
                                    json.encodeToString(authResponse(refreshToken = "committed-refresh")),
                                )
                            }
                            SYNC_PATH -> respondAccountJson(json.encodeToString(SyncResponse(version = 5)))
                            else -> error("Unexpected request")
                        }
                    },
                )
            val repository = accountRepository(api, store)
            repository.start()
            awaitAccountState(repository) { it is AccountState.RestoreFailed }
            assertEquals("stored-refresh-token", backing.text(KEY_REFRESH_TOKEN))
            assertNotNull(backing.text("pending_refresh"))
            assertEquals(0, backing.clearCount)
            failWrite = false
            repository.retryRestore()
            awaitAccountState(repository) { it is AccountState.SignedIn && it.syncVersion == 5L }
            assertEquals(requests[0], requests[1])
            assertEquals("committed-refresh", backing.text(KEY_REFRESH_TOKEN))
        }

    private companion object {
        const val REFRESH_PATH = "/api/v1/auth/refresh"
        const val SYNC_PATH = "/api/v1/account/sync"
        const val PASSWORD_PATH = "/api/v1/account/password"
        const val INVITES_PATH = "/api/v1/account/invites"
    }
}

private suspend fun awaitAccountState(
    repository: AccountRepository,
    predicate: (AccountState) -> Boolean,
): AccountState =
    withContext(Dispatchers.Default) {
        withTimeout(5_000) { repository.state.first(predicate) }
    }

private fun accountRepository(
    api: AccountApi,
    secureStore: SecureStore,
    accessTokenSource: AccountAccessTokenSource = AccountAccessTokenSource(),
    restoreRequestTimeoutMillis: Long = 15_000,
): AccountRepository {
    val settings = MapSettings()
    val registry = ServerRegistry(settings, TestSecureStore())
    return AccountRepository(
        api = api,
        secureStore = secureStore,
        crypto = VaultCrypto(FastAccountCryptoPrimitives()),
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
        accessTokenSource = accessTokenSource,
        restoreRequestTimeoutMillis = restoreRequestTimeoutMillis,
    )
}

private class RecordingAccountSecureStore : SecureStore {
    private val values = mutableMapOf<String, ByteArray>()
    val putKeys = mutableListOf<String>()
    var clearCount: Int = 0
        private set

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(
        key: String,
        value: ByteArray,
    ) {
        values[key] = value.copyOf()
        putKeys += key
    }

    override fun remove(key: String): Boolean =
        values.remove(key)?.let {
            it.fill(0)
            true
        } ?: false

    override fun clear() {
        values.values.forEach { it.fill(0) }
        values.clear()
        clearCount += 1
    }

    fun text(key: String): String? = get(key)?.decodeToString()

    fun snapshot(): Map<String, List<Byte>> = values.mapValues { (_, value) -> value.toList() }

    fun resetObservations() {
        putKeys.clear()
        clearCount = 0
    }

    fun seedStoredSession() {
        put(KEY_REFRESH_TOKEN, "stored-refresh-token".encodeToByteArray())
        put(KEY_VAULT_KEY, ByteArray(VaultCrypto.AES_KEY_SIZE_BYTES) { (it + 1).toByte() })
        put(KEY_WRAP_SALT, ByteArray(16) { (it + 31).toByte() })
        put(KEY_WRAP_NONCE, ByteArray(VaultCrypto.GCM_NONCE_SIZE_BYTES) { (it + 61).toByte() })
        put(KEY_WRAPPED_VAULT, ByteArray(48) { (it + 91).toByte() })
        put(KEY_WRAP_VERSION, "1".encodeToByteArray())
        put(KEY_WRAP_KDF, "PBKDF2-HMAC-SHA256".encodeToByteArray())
        put(KEY_WRAP_ITERATIONS, VaultCrypto.DEFAULT_PBKDF2_ITERATIONS.toString().encodeToByteArray())
        resetObservations()
    }
}

private class FastAccountCryptoPrimitives : CryptoPrimitives {
    override fun sha256(value: ByteArray): ByteArray =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(value)

    private var nextRandomByte = 1

    override fun randomBytes(size: Int): ByteArray =
        ByteArray(size) {
            (nextRandomByte++ and 0xff).toByte()
        }

    override fun aesGcmEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        ByteArray(plaintext.size + VaultCrypto.GCM_TAG_SIZE_BYTES) { index ->
            if (index < plaintext.size) {
                (plaintext[index].toInt() xor key[index % key.size].toInt()).toByte()
            } else {
                (nonce[index % nonce.size].toInt() xor aad.size).toByte()
            }
        }

    override fun aesGcmDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray,
    ): ByteArray =
        ByteArray(ciphertext.size - VaultCrypto.GCM_TAG_SIZE_BYTES) { index ->
            (ciphertext[index].toInt() xor key[index % key.size].toInt()).toByte()
        }

    override fun pbkdf2HmacSha256(
        passphrase: CharArray,
        salt: ByteArray,
        iterations: Int,
        outputSizeBytes: Int,
    ): ByteArray =
        ByteArray(outputSizeBytes) { index ->
            val passwordByte = passphrase[index % passphrase.size].code
            (passwordByte xor salt[index % salt.size].toInt() xor iterations).toByte()
        }
}

private const val KEY_REFRESH_TOKEN = "refresh_token"
private const val KEY_VAULT_KEY = "vault_key"
private const val KEY_WRAP_SALT = "vault_wrap_salt"
private const val KEY_WRAP_NONCE = "vault_wrap_nonce"
private const val KEY_WRAPPED_VAULT = "wrapped_vault_key"
private const val KEY_WRAP_VERSION = "vault_wrap_version"
private const val KEY_WRAP_KDF = "vault_wrap_kdf"
private const val KEY_WRAP_ITERATIONS = "vault_wrap_iterations"

private val ALL_LOCAL_VAULT_FIELDS =
    setOf(
        KEY_VAULT_KEY,
        KEY_WRAP_SALT,
        KEY_WRAP_NONCE,
        KEY_WRAPPED_VAULT,
        KEY_WRAP_VERSION,
        KEY_WRAP_KDF,
        KEY_WRAP_ITERATIONS,
    )
