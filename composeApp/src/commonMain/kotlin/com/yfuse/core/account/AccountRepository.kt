package com.yfuse.core.account

import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.DanmakuPreferences
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.data.SkipSegmentPreferences
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.data.UserAgentPreferences
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.logging.AppLog
import com.yfuse.core.security.AesGcmPayload
import com.yfuse.core.security.RecoveryKeyEnvelope
import com.yfuse.core.security.SecureStore
import com.yfuse.core.security.SecureStoreCorruptedException
import com.yfuse.core.security.SecureStoreException
import com.yfuse.core.security.VaultCrypto
import com.yfuse.core.security.base64UrlToBytes
import com.yfuse.core.security.toBase64Url
import com.yfuse.core.sync.CloudSyncSnapshotV1
import com.yfuse.core.sync.ServerSyncManager
import com.yfuse.core.sync.applyCloudSyncSnapshot
import com.yfuse.core.sync.captureCloudSyncSnapshot
import com.yfuse.deviceModel
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Owns the account session and the client-encrypted sync document.
 *
 * Access tokens are memory-only. The rotating refresh token, vault key, and password-wrapped key
 * envelope are stored through Android Keystore-backed [SecureStore]. Sync is deliberately manual:
 * login only reads the cloud version; local data changes only after an explicit upload or restore.
 */
class AccountRepository(
    private val api: AccountApi,
    private val secureStore: SecureStore,
    private val crypto: VaultCrypto,
    private val registry: ServerRegistry,
    private val theme: ThemePreferences,
    private val userAgent: UserAgentPreferences,
    private val watch: WatchTogetherPreferences,
    private val danmaku: DanmakuPreferences,
    private val skip: SkipSegmentPreferences,
    private val serverSync: ServerSyncManager,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    /** Production supplies Main.immediate, the same serial dispatcher used by settings UI. */
    private val mutationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** PBKDF2 and AES work must never block the Compose main thread. */
    private val cryptoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val accessTokenSource: AccountAccessTokenSource = AccountAccessTokenSource(),
    private val calendarFollows: CalendarFollowStore? = null,
    /** A stalled session request must always return the account page to a retryable state. */
    private val restoreRequestTimeoutMillis: Long = 15_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val _state = MutableStateFlow<AccountState>(AccountState.Restoring)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    @Volatile
    private var started = false

    init {
        accessTokenSource.bind(::validAccessTokenForWatch, ::refreshAccessTokenForWatch)
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { restoreSession() }
    }

    /**
     * Runs account work on this repository's scope instead of the caller's.
     *
     * Callers live in Compose, so their scope dies with the screen. Deriving a vault key takes
     * hundreds of thousands of PBKDF2 rounds, and leaving the account page mid-registration used
     * to cancel that step after the session had already been stored, leaving an account that was
     * signed in but permanently had no sync key.
     */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    fun retryRestore() {
        if (_state.value !is AccountState.RestoreFailed) return
        _state.value = AccountState.Restoring
        scope.launch { restoreSession() }
    }

    suspend fun register(
        username: String,
        password: CharArray,
        nickname: String? = null,
        avatarId: Int? = null,
        inviteCode: String? = null,
    ): Result<Unit> =
        detached {
            try {
                runCatching {
                    mutex.withLock {
                        validateCredentials(username, password)
                        val auth =
                            api.register(
                                username = username.trim(),
                                password = password.concatToString(),
                                nickname = nickname?.trim()?.takeIf(String::isNotEmpty),
                                avatarId = avatarId,
                                inviteCode = inviteCode?.trim()?.takeIf(String::isNotEmpty),
                                deviceName = deviceModel().take(64),
                            )
                        acceptAuth(auth)
                        initializeEmptyVaultLocked(auth.user.id, password)
                        _state.value =
                            requireSignedIn().copy(
                                cloudHasData = false,
                                message = "账号已创建，可手动上传本机数据",
                            )
                    }
                }.onFailure(::recordFailure)
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun login(
        username: String,
        password: CharArray,
    ): Result<Unit> =
        detached {
            try {
                runCatching {
                    mutex.withLock {
                        validateCredentials(username, password)
                        val auth =
                            api.login(
                                username.trim(),
                                password.concatToString(),
                                deviceModel().take(64),
                            )
                        acceptAuth(auth)
                        val remote = authorized { api.getSync(it) }
                        if (remote.payload == null) {
                            initializeEmptyVaultLocked(auth.user.id, password)
                            _state.value =
                                requireSignedIn().copy(
                                    syncVersion = remote.version,
                                    cloudHasData = false,
                                    message = "账号已登录，云端暂无数据，可手动上传",
                                )
                        } else {
                            require(remote.version > 0L) { "云端同步版本无效" }
                            val payload = remote.payload
                            val recovery = payload.toRecoveryEnvelope()
                            val vaultKey =
                                withContext(cryptoDispatcher) {
                                    crypto.unwrapVaultKey(
                                        envelope = recovery,
                                        passphrase = password,
                                        aad = payload.recoveryAad(auth.user.id),
                                    )
                                }
                            try {
                                storeVault(auth.user.id, vaultKey, recovery)
                            } finally {
                                vaultKey.fill(0)
                            }
                            _state.value =
                                requireSignedIn().copy(
                                    syncVersion = remote.version,
                                    cloudHasData = true,
                                    message = "云端版本 ${remote.version} 已就绪，点“恢复云端”后才会覆盖本机",
                                )
                        }
                    }
                }.onFailure(::recordFailure)
            } finally {
                password.fill('\u0000')
            }
        }

    suspend fun uploadNow(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    requireSignedIn()
                    val remote = authorized { api.getSync(it) }
                    val vaultKey = requireVaultKey()
                    try {
                        uploadLocked(
                            baseVersion = remote.version,
                            vaultKey = vaultKey,
                            remotePayload = remote.payload,
                            successMessage = "已用本机数据覆盖云端",
                        )
                    } finally {
                        vaultKey.fill(0)
                    }
                }
            }.onFailure(::recordFailure)
        }

    suspend fun downloadNow(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    val expectedLocal = capturePlaintext()
                    val remote = authorized { api.getSync(it) }
                    if (remote.payload == null) {
                        _state.value =
                            requireSignedIn().copy(
                                syncVersion = remote.version,
                                cloudHasData = false,
                                message = "服务器暂无同步数据",
                            )
                        return@withLock
                    }
                    val vaultKey = requireVaultKey()
                    try {
                        decryptAndApplyLocked(remote, vaultKey, expectedLocal)
                    } finally {
                        vaultKey.fill(0)
                    }
                }
            }.onFailure(::recordFailure)
        }

    suspend fun clearRemoteSync(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    val cleared = authorized { api.clearSync(it) }
                    require(cleared.payload == null) { "服务器清空响应无效" }
                    _state.value =
                        requireSignedIn().copy(
                            syncVersion = cleared.version,
                            cloudHasData = false,
                            syncing = false,
                            lastSyncedAtEpochMs = null,
                            message = "服务器同步数据已清空，本机数据和账号仍保留",
                        )
                }
            }.onFailure(::recordFailure)
        }

    suspend fun changePassword(
        currentPassword: CharArray,
        newPassword: CharArray,
    ): Result<Unit> =
        detached {
            try {
                runCatching {
                    mutex.withLock {
                        require(currentPassword.size in 1..128) { "请输入当前密码" }
                        require(newPassword.size in MIN_PASSWORD_CHARS..128) {
                            "新密码需为 $MIN_PASSWORD_CHARS–128 个字符"
                        }
                        require(!currentPassword.contentEquals(newPassword)) { "新密码不能与当前密码相同" }
                        val signedIn = requireSignedIn()
                        val remote = authorized { api.getSync(it) }
                        remote.payload?.requireSupportedMetadata()
                        val cloudKeyVersion = remote.payload?.keyVersion ?: KEY_VERSION
                        require(cloudKeyVersion == KEY_VERSION) { "暂不支持这个云端密钥版本" }
                        val vaultKey =
                            remote.payload?.let { payload ->
                                val remoteRecovery = payload.toRecoveryEnvelope()
                                withContext(cryptoDispatcher) {
                                    val key =
                                        runCatching {
                                            crypto.unwrapVaultKey(
                                                envelope = remoteRecovery,
                                                passphrase = currentPassword,
                                                aad = payload.recoveryAad(signedIn.session.user.id),
                                            )
                                        }.getOrElse {
                                            throw IllegalArgumentException("当前密码错误或云端加密数据无效")
                                        }
                                    try {
                                        val verifiedPlaintext =
                                            crypto.decrypt(
                                                key = key,
                                                payload =
                                                    AesGcmPayload(
                                                        nonce = payload.nonce.base64UrlToBytes(),
                                                        ciphertext = payload.ciphertext.base64UrlToBytes(),
                                                    ),
                                                aad =
                                                    syncAad(
                                                        signedIn.session.user.id,
                                                        remote.version,
                                                        cloudKeyVersion,
                                                    ),
                                            )
                                        verifiedPlaintext.fill(0)
                                        key
                                    } catch (error: Throwable) {
                                        key.fill(0)
                                        throw IllegalArgumentException("当前密码错误或云端加密数据无效", error)
                                    }
                                }
                            } ?: requireVaultKey()
                        try {
                            val recovery =
                                withContext(cryptoDispatcher) {
                                    crypto.wrapVaultKey(
                                        vaultKey = vaultKey,
                                        passphrase = newPassword,
                                        aad =
                                            recoveryAad(
                                                userId = signedIn.session.user.id,
                                                keyVersion = cloudKeyVersion,
                                                wrapVersion = WRAP_VERSION,
                                                wrapKdf = WRAP_KDF,
                                                wrapIterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS,
                                            ),
                                    )
                                }
                            val auth =
                                authorized { accessToken ->
                                    api.changePassword(
                                        accessToken = accessToken,
                                        request =
                                            ChangePasswordRequest(
                                                currentPassword = currentPassword.concatToString(),
                                                newPassword = newPassword.concatToString(),
                                                expectedSyncVersion = remote.version,
                                                keyVersion = cloudKeyVersion,
                                                wrappedVaultKey = recovery.wrappedKey.ciphertext.toBase64Url(),
                                                wrapSalt = recovery.salt.toBase64Url(),
                                                wrapNonce = recovery.wrappedKey.nonce.toBase64Url(),
                                                wrapVersion = recovery.version,
                                                wrapKdf = WRAP_KDF,
                                                wrapIterations = recovery.iterations,
                                                deviceName = deviceModel().take(64),
                                            ),
                                    )
                                }
                            acceptAuth(auth)
                            storeVault(signedIn.session.user.id, vaultKey, recovery)
                            _state.value =
                                requireSignedIn().copy(
                                    syncVersion = remote.version,
                                    cloudHasData = remote.payload != null,
                                    message = "登录密码已修改，加密密钥已同步更新",
                                )
                        } finally {
                            vaultKey.fill(0)
                        }
                    }
                }.onFailure(::recordFailure)
            } finally {
                currentPassword.fill('\u0000')
                newPassword.fill('\u0000')
            }
        }

    suspend fun updateProfile(
        nickname: String,
        avatarId: Int,
    ): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    val updated =
                        authorized {
                            api.updateProfile(
                                accessToken = it,
                                nickname = nickname.trim(),
                                avatarId = avatarId,
                            )
                        }
                    val current = requireSignedIn()
                    _state.value = current.copy(session = current.session.copy(user = updated), message = null)
                    watch.setProfile(updated.nickname, updated.avatarId)
                }
            }.onFailure(::recordFailure)
        }

    suspend fun logout(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    val access = (_state.value as? AccountState.SignedIn)?.session?.accessToken
                    if (access != null) runCatching { api.logout(access) }
                    secureStore.clear()
                    setSignedOut()
                }
            }
        }

    suspend fun sessions(): Result<List<AccountDeviceSession>> =
        detached {
            runCatching { mutex.withLock { authorized(api::sessions) } }
        }

    suspend fun issueInvite(): Result<IssuedInviteCode> =
        detached {
            runCatching {
                mutex.withLock {
                    val signedIn = requireSignedIn()
                    require(signedIn.session.user.canIssueInvites()) {
                        "你没有生成邀请码的权限"
                    }
                    authorized(api::issueInvite).also { issued ->
                        require(issued.code.length in 12..128) { "服务器返回的邀请码无效" }
                        require(issued.expiresAtEpochMs > nowEpochMs()) { "服务器返回的邀请码已过期" }
                    }
                }
            }.onFailure { error ->
                if (error is AccountApiException && error.status == HttpStatusCode.Forbidden) {
                    removeInviteCapability()
                }
                recordFailure(error)
            }
        }

    suspend fun revokeSession(sessionId: String): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    val current = requireSignedIn()
                    val target =
                        authorized(api::sessions).firstOrNull { it.id == sessionId }
                            ?: error("设备会话不存在")
                    authorized { api.revokeSession(it, sessionId) }
                    if (target.current) {
                        secureStore.clear()
                        setSignedOut()
                    } else {
                        _state.value = current.copy(message = "设备已退出")
                    }
                }
            }.onFailure(::recordFailure)
        }

    suspend fun revokeOtherSessions(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    authorized(api::revokeOtherSessions)
                    _state.value = requireSignedIn().copy(message = "其他设备已全部退出")
                }
            }.onFailure(::recordFailure)
        }

    suspend fun revokeAllSessions(): Result<Unit> =
        detached {
            runCatching {
                mutex.withLock {
                    authorized(api::revokeAllSessions)
                    secureStore.clear()
                    setSignedOut()
                }
            }
        }

    suspend fun exportAccount(): Result<String> =
        detached {
            runCatching {
                mutex.withLock {
                    val value = authorized(api::exportAccount)
                    json.encodeToString(value)
                }
            }.onFailure(::recordFailure)
        }

    suspend fun deleteAccount(password: CharArray): Result<Unit> =
        detached {
            try {
                runCatching {
                    mutex.withLock {
                        require(password.isNotEmpty()) { "请输入当前密码" }
                        authorized { api.deleteAccount(it, password.concatToString()) }
                        secureStore.clear()
                        setSignedOut()
                    }
                }.onFailure(::recordFailure)
            } finally {
                password.fill('\u0000')
            }
        }

    private suspend fun restoreSession() {
        mutex.withLock {
            var restoringCredentials = true
            runCatching {
                val refresh = secureStore.get(KEY_REFRESH_TOKEN)?.decodeToString()
                if (refresh.isNullOrBlank()) {
                    setSignedOut()
                    return@runCatching
                }
                val auth =
                    withTimeout(restoreRequestTimeoutMillis) {
                        requestRefresh(refresh)
                    }
                acceptAuth(auth)
                restoringCredentials = false
                val remote =
                    withTimeout(restoreRequestTimeoutMillis) {
                        authorized { api.getSync(it) }
                    }
                val localVaultKey = secureStore.get(KEY_VAULT_KEY)
                if (localVaultKey == null) {
                    // The session itself is still good; only the sync key is missing, which is
                    // what a registration that failed to finish its vault leaves behind. Signing
                    // out here looked to the user like the login had never been saved.
                    _state.value =
                        requireSignedIn().copy(
                            syncVersion = remote.version,
                            cloudHasData = remote.payload != null,
                            syncing = false,
                            message = "本机同步密钥缺失，请重新登录一次以恢复同步",
                        )
                    return@runCatching
                }
                try {
                    require(localVaultKey.size == VaultCrypto.AES_KEY_SIZE_BYTES) {
                        "本机同步密钥无效"
                    }
                } finally {
                    localVaultKey.fill(0)
                }
                // Repair a vault whose wrap entries were lost, so the next sync does not report a
                // missing key for material the server can hand back.
                if (readStoredRecovery() == null) healLocalWrapFromCloud(remote.payload)
                require(remote.version >= 0L) { "云端同步版本无效" }
                _state.value =
                    requireSignedIn().copy(
                        syncVersion = remote.version,
                        cloudHasData = remote.payload != null,
                        message =
                            if (remote.payload == null) {
                                "云端暂无数据，可手动上传"
                            } else {
                                "云端版本 ${remote.version} 已就绪，点“恢复云端”后才会覆盖本机"
                            },
                    )
            }.onFailure { error ->
                if (!restoringCredentials && _state.value is AccountState.SignedOut) return@onFailure
                val phase = if (restoringCredentials) "refresh" else "sync"
                if (
                    restoringCredentials &&
                    (
                        error is SecureStoreCorruptedException ||
                            error is AccountApiException &&
                            error.status == HttpStatusCode.Unauthorized
                    )
                ) {
                    logRestoreFailure(error, phase = phase, outcome = "signed_out")
                    runCatching { secureStore.clear() }
                    setSignedOut()
                } else {
                    val current = _state.value as? AccountState.SignedIn
                    if (current != null) {
                        logRestoreFailure(error, phase = phase, outcome = "signed_in_without_cloud")
                        _state.value =
                            current.copy(
                                syncing = false,
                                message = "已登录，暂时无法读取云端数据",
                            )
                    } else {
                        logRestoreFailure(error, phase = phase, outcome = "retryable")
                        _state.value = AccountState.RestoreFailed(restoreFailureMessage(error))
                    }
                }
            }
        }
    }

    /**
     * The diagnostic package used to carry nothing about the account page: a device that showed
     * "暂时无法恢复账号" exported a log with no account entry at all, so the cause (server down,
     * expired refresh token, timeout) could only be guessed. Tokens never enter the log; the
     * status and exception type are enough to tell those apart.
     */
    private fun logRestoreFailure(
        error: Throwable,
        phase: String,
        outcome: String,
    ) {
        AppLog.warning(
            category = "account",
            event = "restore_failed",
            message = "Account session restore failed",
            throwable = error,
            attributes =
                mapOf(
                    "phase" to phase,
                    "reason" to restoreFailureReason(error),
                    "outcome" to outcome,
                ),
        )
    }

    private suspend fun validAccessTokenForWatch(): String? =
        detached {
            mutex.withLock {
                if (_state.value !is AccountState.SignedIn) return@withLock null
                authorized { it }
            }
        }

    private suspend fun refreshAccessTokenForWatch(): String? =
        detached {
            mutex.withLock {
                if (_state.value !is AccountState.SignedIn) return@withLock null
                refreshLocked().session.accessToken
            }
        }

    private suspend fun uploadLocked(
        baseVersion: Long,
        vaultKey: ByteArray,
        remotePayload: EncryptedSyncPayload?,
        successMessage: String = "已安全同步",
    ) {
        val signedIn = requireSignedIn()
        // The wrap only opens with the account password, so the copy the server already holds is
        // as good as the local one. Rebuild from it when this device's copy is gone, and fall back
        // to letting the server carry its own wrap forward rather than refusing to sync.
        val recovery = readStoredRecovery() ?: healLocalWrapFromCloud(remotePayload)
        require(recovery != null || remotePayload?.keyVersion == KEY_VERSION) {
            "本机缺少同步密钥信息，请重新登录后再上传"
        }
        // Keep the previous message in place. Dropping it here and restoring it a moment later
        // collapses and re-expands the card, which reads as the whole screen flashing.
        _state.value = signedIn.copy(syncing = true)
        val plaintext = capturePlaintext()
        require(plaintext.encodeToByteArray().size <= MAX_SYNC_PLAINTEXT_BYTES) {
            "同步数据过大，请减少弹幕绑定或服务器数量"
        }
        val nextVersion = baseVersion + 1
        val encrypted =
            withContext(cryptoDispatcher) {
                crypto.encrypt(
                    key = vaultKey,
                    plaintext = plaintext.encodeToByteArray(),
                    aad = syncAad(signedIn.session.user.id, nextVersion, KEY_VERSION),
                )
            }
        val payload =
            EncryptedSyncPayload(
                keyVersion = KEY_VERSION,
                nonce = encrypted.nonce.toBase64Url(),
                ciphertext = encrypted.ciphertext.toBase64Url(),
                wrappedVaultKey = recovery?.wrappedKey?.ciphertext?.toBase64Url(),
                wrapSalt = recovery?.salt?.toBase64Url(),
                wrapNonce = recovery?.wrappedKey?.nonce?.toBase64Url(),
                wrapVersion = recovery?.version,
                wrapKdf = recovery?.let { WRAP_KDF },
                wrapIterations = recovery?.iterations,
            )
        val response = authorized { api.putSync(it, baseVersion, payload) }
        val current = requireSignedIn()
        _state.value =
            current.copy(
                syncVersion = response.version,
                cloudHasData = true,
                syncing = false,
                lastSyncedAtEpochMs = response.updatedAtEpochMs ?: nowEpochMs(),
                message = successMessage,
            )
    }

    private suspend fun decryptAndApplyLocked(
        remote: SyncResponse,
        vaultKey: ByteArray,
        expectedLocalPlaintext: String? = null,
    ) {
        val signedIn = requireSignedIn()
        val payload = requireNotNull(remote.payload) { "云端同步数据为空" }
        payload.requireSupportedMetadata()
        val plaintextBytes =
            withContext(cryptoDispatcher) {
                crypto.decrypt(
                    key = vaultKey,
                    payload =
                        AesGcmPayload(
                            nonce = payload.nonce.base64UrlToBytes(),
                            ciphertext = payload.ciphertext.base64UrlToBytes(),
                        ),
                    aad = syncAad(signedIn.session.user.id, remote.version, payload.keyVersion),
                )
            }
        try {
            require(plaintextBytes.size <= MAX_SYNC_PLAINTEXT_BYTES) { "云端同步数据过大" }
            val snapshot = json.decodeFromString<CloudSyncSnapshotV1>(plaintextBytes.decodeToString())
            withContext(mutationDispatcher) {
                val localChanged =
                    expectedLocalPlaintext != null &&
                        capturePlaintextOnMutationDispatcher() != expectedLocalPlaintext
                if (localChanged) {
                    error("同步期间本机设置发生变化，已取消云端覆盖，请重试")
                }
                // This synchronous apply now runs on the same serial dispatcher as Compose UI
                // mutations, so no server edit can interleave after the second snapshot check.
                applyCloudSyncSnapshot(
                    snapshot,
                    registry,
                    theme,
                    userAgent,
                    watch,
                    danmaku,
                    skip,
                    serverSync,
                    calendarFollows,
                ).getOrThrow()
            }
            _state.value =
                signedIn.copy(
                    syncVersion = remote.version,
                    cloudHasData = true,
                    syncing = false,
                    lastSyncedAtEpochMs = remote.updatedAtEpochMs ?: nowEpochMs(),
                    message = "已从云端恢复",
                )
        } finally {
            plaintextBytes.fill(0)
        }
    }

    private fun acceptAuth(auth: AuthResponse) {
        secureStore.put(KEY_REFRESH_TOKEN, auth.refreshToken.encodeToByteArray())
        secureStore.remove(KEY_PENDING_REFRESH)
        watch.setProfile(auth.user.nickname, auth.user.avatarId)
        val previous = _state.value as? AccountState.SignedIn
        _state.value =
            AccountState.SignedIn(
                session =
                    AccountSession(
                        user = auth.user,
                        accessToken = auth.accessToken,
                        accessExpiresAtEpochMs = auth.accessExpiresAtEpochMs,
                        refreshExpiresAtEpochMs = auth.refreshExpiresAtEpochMs,
                    ),
                syncVersion = previous?.syncVersion ?: 0,
                cloudHasData = previous?.cloudHasData ?: false,
                lastSyncedAtEpochMs = previous?.lastSyncedAtEpochMs,
            )
        accessTokenSource.markAvailable()
    }

    private fun removeInviteCapability() {
        val current = _state.value as? AccountState.SignedIn ?: return
        val user = current.session.user
        if (INVITE_ISSUE_CAPABILITY !in user.capabilities) return
        _state.value =
            current.copy(
                session =
                    current.session.copy(
                        user =
                            user.copy(
                                capabilities = user.capabilities - INVITE_ISSUE_CAPABILITY,
                            ),
                    ),
            )
    }

    private fun setSignedOut() {
        _state.value = AccountState.SignedOut
        accessTokenSource.markUnavailable()
    }

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        var session = requireSignedIn().session
        if (session.accessExpiresAtEpochMs <= nowEpochMs() + ACCESS_REFRESH_SKEW_MS) {
            session = refreshLocked().session
        }
        return try {
            block(session.accessToken)
        } catch (error: AccountApiException) {
            if (error.status != HttpStatusCode.Unauthorized) throw error
            block(refreshLocked().session.accessToken)
        }
    }

    private suspend fun refreshLocked(): AccountState.SignedIn {
        val token = secureStore.get(KEY_REFRESH_TOKEN)?.decodeToString()?.takeIf(String::isNotBlank)
        if (token == null) {
            runCatching { secureStore.clear() }
            setSignedOut()
            error("登录状态已失效，请重新登录")
        }
        try {
            acceptAuth(requestRefresh(token))
        } catch (error: AccountApiException) {
            if (error.status == HttpStatusCode.Unauthorized) {
                secureStore.clear()
                setSignedOut()
            }
            throw error
        }
        return requireSignedIn()
    }

    private suspend fun requestRefresh(token: String): AuthResponse {
        val stored = secureStore.get(KEY_PENDING_REFRESH)?.decodeToString()
        val pending =
            stored
                ?.let { json.decodeFromString<PendingAccountRefresh>(it) }
                ?.takeIf { it.refreshToken == token }
                ?: PendingAccountRefresh(
                    refreshToken = token,
                    requestId =
                        crypto.generateVaultKey().let { bytes ->
                            try {
                                bytes.toBase64Url()
                            } finally {
                                bytes.fill(0)
                            }
                        },
                )
        // Persist before sending, and reuse after timeout, process death, or a failed token write.
        secureStore.put(KEY_PENDING_REFRESH, json.encodeToString(pending).encodeToByteArray())
        return api.refresh(token, deviceModel().take(64), pending.requestId)
    }

    /**
     * Writes the vault key last, so its presence means the whole bundle landed. A half-written
     * vault is rolled back instead of being left for the next sync to trip over.
     */
    private fun storeVault(
        userId: String,
        vaultKey: ByteArray,
        recovery: RecoveryKeyEnvelope,
    ) {
        try {
            storeWrap(recovery)
            secureStore.put(KEY_VAULT_USER_ID, userId.encodeToByteArray())
            secureStore.put(KEY_VAULT_KEY, vaultKey)
        } catch (error: Throwable) {
            runCatching { clearVaultSecrets() }
            throw error
        }
    }

    private fun storeWrap(recovery: RecoveryKeyEnvelope) {
        secureStore.put(KEY_WRAP_SALT, recovery.salt)
        secureStore.put(KEY_WRAP_NONCE, recovery.wrappedKey.nonce)
        secureStore.put(KEY_WRAPPED_VAULT, recovery.wrappedKey.ciphertext)
        secureStore.put(KEY_WRAP_VERSION, recovery.version.toString().encodeToByteArray())
        secureStore.put(KEY_WRAP_KDF, WRAP_KDF.encodeToByteArray())
        secureStore.put(KEY_WRAP_ITERATIONS, recovery.iterations.toString().encodeToByteArray())
    }

    private fun readStoredRecovery(): RecoveryKeyEnvelope? {
        val salt = secureStore.get(KEY_WRAP_SALT) ?: return null
        val nonce = secureStore.get(KEY_WRAP_NONCE) ?: return null
        val wrapped = secureStore.get(KEY_WRAPPED_VAULT) ?: return null
        val version =
            secureStore.get(KEY_WRAP_VERSION)?.decodeToString()?.toIntOrNull()
                ?: return null
        val kdf = secureStore.get(KEY_WRAP_KDF)?.decodeToString() ?: return null
        val iterations =
            secureStore.get(KEY_WRAP_ITERATIONS)?.decodeToString()?.toIntOrNull()
                ?: return null
        if (version != WRAP_VERSION || kdf != WRAP_KDF) return null
        // Sizes and iteration bounds are enforced by the envelope itself; treat a stored bundle
        // that no longer satisfies them as absent so the cloud copy can replace it.
        return runCatching {
            RecoveryKeyEnvelope(
                version = version,
                salt = salt,
                iterations = iterations,
                wrappedKey = AesGcmPayload(nonce, wrapped),
            )
        }.getOrNull()
    }

    /** Restores the local key-wrap entries from the copy the sync response already carries. */
    private fun healLocalWrapFromCloud(payload: EncryptedSyncPayload?): RecoveryKeyEnvelope? {
        if (payload == null || payload.keyVersion != KEY_VERSION) return null
        val recovery = runCatching { payload.toRecoveryEnvelope() }.getOrNull() ?: return null
        runCatching { storeWrap(recovery) }
        return recovery
    }

    private suspend fun initializeEmptyVaultLocked(
        userId: String,
        password: CharArray,
    ) {
        val vaultKey = crypto.generateVaultKey()
        try {
            val recovery =
                withContext(cryptoDispatcher) {
                    crypto.wrapVaultKey(
                        vaultKey = vaultKey,
                        passphrase = password,
                        aad =
                            recoveryAad(
                                userId = userId,
                                keyVersion = KEY_VERSION,
                                wrapVersion = WRAP_VERSION,
                                wrapKdf = WRAP_KDF,
                                wrapIterations = VaultCrypto.DEFAULT_PBKDF2_ITERATIONS,
                            ),
                    )
                }
            storeVault(userId, vaultKey, recovery)
        } finally {
            vaultKey.fill(0)
        }
    }

    private fun clearVaultSecrets() {
        listOf(
            KEY_VAULT_KEY,
            KEY_VAULT_USER_ID,
            KEY_WRAP_SALT,
            KEY_WRAP_NONCE,
            KEY_WRAPPED_VAULT,
            KEY_WRAP_VERSION,
            KEY_WRAP_KDF,
            KEY_WRAP_ITERATIONS,
        ).forEach { secureStore.remove(it) }
    }

    private suspend fun capturePlaintext(): String {
        // Only the snapshot read has to share the UI's serial dispatcher. Serializing it there
        // too would block the main thread for the whole encode, which janks the sync screen.
        val snapshot =
            withContext(mutationDispatcher) {
                captureCloudSyncSnapshot(
                    registry,
                    theme,
                    userAgent,
                    watch,
                    danmaku,
                    skip,
                    serverSync,
                    calendarFollows,
                )
            }
        return withContext(cryptoDispatcher) { json.encodeToString(snapshot) }
    }

    private fun capturePlaintextOnMutationDispatcher(): String =
        json.encodeToString(
            captureCloudSyncSnapshot(
                registry,
                theme,
                userAgent,
                watch,
                danmaku,
                skip,
                serverSync,
                calendarFollows,
            ),
        )

    private fun EncryptedSyncPayload.toRecoveryEnvelope(): RecoveryKeyEnvelope {
        requireSupportedMetadata()
        val version = requireNotNull(wrapVersion) { "云端缺少密钥恢复版本" }
        val kdf = requireNotNull(wrapKdf) { "云端缺少密钥恢复算法" }
        val iterations = requireNotNull(wrapIterations) { "云端缺少密钥恢复参数" }
        require(version == WRAP_VERSION && kdf == WRAP_KDF) { "暂不支持这个密钥恢复版本" }
        val salt = requireNotNull(wrapSalt) { "云端缺少密钥恢复信息" }.base64UrlToBytes()
        val nonce = requireNotNull(wrapNonce) { "云端缺少密钥恢复信息" }.base64UrlToBytes()
        val wrapped =
            requireNotNull(wrappedVaultKey) { "云端缺少密钥恢复信息" }
                .base64UrlToBytes()
        return RecoveryKeyEnvelope(
            version = version,
            salt = salt,
            iterations = iterations,
            wrappedKey = AesGcmPayload(nonce, wrapped),
        )
    }

    private fun EncryptedSyncPayload.requireSupportedMetadata() {
        require(
            schemaVersion == SYNC_SCHEMA_VERSION &&
                algorithm == "AES-256-GCM" &&
                keyVersion == KEY_VERSION,
        ) { "暂不支持这个加密数据版本" }
    }

    private fun EncryptedSyncPayload.recoveryAad(userId: String): ByteArray =
        recoveryAad(
            userId = userId,
            keyVersion = keyVersion,
            wrapVersion = requireNotNull(wrapVersion) { "云端缺少密钥恢复版本" },
            wrapKdf = requireNotNull(wrapKdf) { "云端缺少密钥恢复算法" },
            wrapIterations = requireNotNull(wrapIterations) { "云端缺少密钥恢复参数" },
        )

    private fun requireSignedIn(): AccountState.SignedIn =
        _state.value as? AccountState.SignedIn ?: error("请先登录 Yfuse 账号")

    private fun requireVaultKey(): ByteArray {
        val userId = requireSignedIn().session.user.id
        val vaultKey = secureStore.get(KEY_VAULT_KEY) ?: error("本机缺少同步密钥，请重新登录")
        require(vaultKey.size == VaultCrypto.AES_KEY_SIZE_BYTES) { "本机同步密钥无效，请重新登录" }
        when (secureStore.get(KEY_VAULT_USER_ID)?.decodeToString()) {
            // Installs that predate the owner tag hold a vault for the account that stored it.
            null -> secureStore.put(KEY_VAULT_USER_ID, userId.encodeToByteArray())
            userId -> Unit
            else -> {
                vaultKey.fill(0)
                error("本机保存的是其他账号的同步密钥，请重新登录")
            }
        }
        return vaultKey
    }

    private fun recordFailure(error: Throwable) {
        val current = _state.value as? AccountState.SignedIn ?: return
        val message =
            when (error) {
                is AccountApiException ->
                    when (error.code) {
                        "invalid_credentials" -> "账号或密码错误"
                        "current_password_invalid" -> "当前密码错误"
                        "password_invalid" -> "新密码不符合要求"
                        "username_unavailable" -> "这个账号名已被使用"
                        "sync_version_conflict" -> "云端已有更新，请先从云端恢复"
                        "rate_limited" -> "尝试次数过多，请稍后再试"
                        "invite_invalid" -> "邀请码无效或已使用"
                        "forbidden" -> "你没有生成邀请码的权限"
                        else -> error.message
                    }
                else -> error.message ?: "账号同步失败"
            }
        _state.value = current.copy(syncing = false, message = message)
    }

    private fun validateCredentials(
        username: String,
        password: CharArray,
    ) {
        val normalized = username.trim()
        require(USERNAME_PATTERN.matches(normalized)) {
            "账号名需为 3–40 位字母、数字、点、横线或下划线，且以字母或数字开头"
        }
        require(password.size in MIN_PASSWORD_CHARS..128) { "密码需为 $MIN_PASSWORD_CHARS–128 个字符" }
    }

    private fun syncAad(
        userId: String,
        version: Long,
        keyVersion: Int,
    ): ByteArray = "yfuse-sync:v$SYNC_SCHEMA_VERSION:$userId:$version:$keyVersion".encodeToByteArray()

    private fun recoveryAad(
        userId: String,
        keyVersion: Int,
        wrapVersion: Int,
        wrapKdf: String,
        wrapIterations: Int,
    ): ByteArray =
        "yfuse-vault-key:v1:$userId:$keyVersion:$wrapVersion:$wrapKdf:$wrapIterations"
            .encodeToByteArray()

    private companion object {
        const val MIN_PASSWORD_CHARS = 8
        const val KEY_VERSION = 1
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_PENDING_REFRESH = "pending_refresh"
        const val KEY_VAULT_KEY = "vault_key"
        const val KEY_VAULT_USER_ID = "vault_user_id"
        const val KEY_WRAP_SALT = "vault_wrap_salt"
        const val KEY_WRAP_NONCE = "vault_wrap_nonce"
        const val KEY_WRAPPED_VAULT = "wrapped_vault_key"
        const val KEY_WRAP_VERSION = "vault_wrap_version"
        const val KEY_WRAP_KDF = "vault_wrap_kdf"
        const val KEY_WRAP_ITERATIONS = "vault_wrap_iterations"
        const val SYNC_SCHEMA_VERSION = 1
        const val WRAP_VERSION = RecoveryKeyEnvelope.CURRENT_VERSION
        const val WRAP_KDF = "PBKDF2-HMAC-SHA256"

        // The server accepts at most 256 KiB of ciphertext; AES-GCM appends a 16-byte tag.
        const val MAX_SYNC_PLAINTEXT_BYTES = 256 * 1024 - VaultCrypto.GCM_TAG_SIZE_BYTES
        const val ACCESS_REFRESH_SKEW_MS = 30_000L
        val USERNAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,39}")
    }
}

/**
 * Wording for a restore that failed without invalidating the stored session.
 *
 * Every non-Keystore failure used to read "网络暂不可用". When the account service itself is down,
 * or a Cloudflare edge in front of it answers 530, or the request times out, that copy sends
 * the user off to toggle Wi-Fi while nothing on the phone is wrong. Name the layer that failed.
 */
internal fun restoreFailureMessage(error: Throwable): String =
    when {
        error is SecureStoreException -> "本机登录信息暂时无法读取或保存，请重试。"
        error is AccountApiException ->
            if (error.status.value >= 500) {
                "账号服务暂时不可用（HTTP ${error.status.value}），本机登录信息仍已安全保留。"
            } else {
                "账号服务返回异常（HTTP ${error.status.value}），本机登录信息仍已安全保留。"
            }
        error.isRestoreTimeout() -> "账号服务响应超时，本机登录信息仍已安全保留。"
        else -> "网络暂不可用，本机登录信息仍已安全保留。"
    }

/** Compact, token-free classification for the diagnostic log. */
internal fun restoreFailureReason(error: Throwable): String =
    when {
        error is SecureStoreCorruptedException -> "secure_store_corrupted"
        error is SecureStoreException -> "secure_store"
        error is AccountApiException -> "http_${error.status.value}:${error.code}"
        error.isRestoreTimeout() -> "timeout"
        else -> "network:${error::class.simpleName ?: "unknown"}"
    }

private fun Throwable.isRestoreTimeout(): Boolean =
    this is TimeoutCancellationException ||
        this is HttpRequestTimeoutException ||
        this is ConnectTimeoutException ||
        this is SocketTimeoutException
