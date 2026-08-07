package com.yfuse.watch.account

import java.io.File
import java.util.Base64
import java.util.Locale
import java.util.UUID

internal enum class AccountProblem {
    InvalidRequest,
    InvalidCredentials,
    Unauthorized,
    UsernameUnavailable,
    VersionConflict,
    NonceReused,
    RateLimited,
    RegistrationClosed,
    CurrentPasswordInvalid,
}

internal class AccountServiceException(
    val problem: AccountProblem,
    val safeCode: String,
    val safeMessage: String,
    val currentVersion: Long? = null,
    val retryAfterSeconds: Long? = null,
) : RuntimeException(safeMessage)

/** Owns the persistence and security primitives used by the HTTP account routes. */
class AccountBackend private constructor(
    internal val service: AccountService,
    private val store: AccountStore,
    private val workExecutor: AccountWorkExecutor,
) : AutoCloseable {
    internal suspend fun <T> execute(block: AccountService.() -> T): T =
        workExecutor.execute { service.block() }

    override fun close() {
        try {
            workExecutor.close()
        } finally {
            store.close()
        }
    }

    companion object {
        fun sqlite(
            databaseFile: File,
            registrationPolicy: AccountRegistrationPolicy =
                AccountRegistrationPolicy.fromEnvironment(),
        ): AccountBackend = create(
            store = SqliteAccountStore.open(databaseFile),
            passwordHasher = Pbkdf2PasswordHasher(),
            workExecutor = AccountWorkExecutor(),
            usernameFailureLimiter = UsernameFailureLimiter(),
            syncUserRateLimiter = AccountRateLimiter(),
            registrationPolicy = registrationPolicy,
        )

        /** Useful for local/test application instances; data intentionally dies with the process. */
        fun inMemory(
            registrationPolicy: AccountRegistrationPolicy = AccountRegistrationPolicy(),
        ): AccountBackend = create(
            store = SqliteAccountStore.inMemory(),
            passwordHasher = Pbkdf2PasswordHasher(),
            workExecutor = AccountWorkExecutor(),
            usernameFailureLimiter = UsernameFailureLimiter(),
            syncUserRateLimiter = AccountRateLimiter(),
            registrationPolicy = registrationPolicy,
        )

        internal fun inMemoryForTests(
            passwordIterations: Int = 1_000,
            clock: () -> Long = System::currentTimeMillis,
            workExecutor: AccountWorkExecutor = AccountWorkExecutor(),
            usernameFailureLimiter: UsernameFailureLimiter = UsernameFailureLimiter(clock = clock),
            syncUserRateLimiter: AccountRateLimiter = AccountRateLimiter(clock = clock),
            nonceHistoryPerUserLimit: Int = 4_096,
            nonceHistoryRetentionMs: Long = 180L * 24 * 60 * 60_000L,
            nonceHistoryCleanupIntervalMs: Long = 60 * 60_000L,
            activeSessionsPerUserLimit: Int = 10,
            registrationPolicy: AccountRegistrationPolicy = AccountRegistrationPolicy(),
        ): AccountBackend = create(
            store = SqliteAccountStore.inMemory(
                nonceHistoryPerUserLimit = nonceHistoryPerUserLimit,
                nonceHistoryRetentionMs = nonceHistoryRetentionMs,
                nonceHistoryCleanupIntervalMs = nonceHistoryCleanupIntervalMs,
                activeSessionsPerUserLimit = activeSessionsPerUserLimit,
            ),
            passwordHasher = Pbkdf2PasswordHasher(passwordIterations),
            clock = clock,
            workExecutor = workExecutor,
            usernameFailureLimiter = usernameFailureLimiter,
            syncUserRateLimiter = syncUserRateLimiter,
            registrationPolicy = registrationPolicy,
        )

        internal fun sqliteForTests(
            databaseFile: File,
            passwordIterations: Int = 1_000,
            clock: () -> Long = System::currentTimeMillis,
            workExecutor: AccountWorkExecutor = AccountWorkExecutor(),
            usernameFailureLimiter: UsernameFailureLimiter = UsernameFailureLimiter(clock = clock),
            syncUserRateLimiter: AccountRateLimiter = AccountRateLimiter(clock = clock),
            nonceHistoryPerUserLimit: Int = 4_096,
            nonceHistoryRetentionMs: Long = 180L * 24 * 60 * 60_000L,
            nonceHistoryCleanupIntervalMs: Long = 60 * 60_000L,
            activeSessionsPerUserLimit: Int = 10,
            registrationPolicy: AccountRegistrationPolicy = AccountRegistrationPolicy(),
        ): AccountBackend = create(
            store = SqliteAccountStore.open(
                databaseFile,
                nonceHistoryPerUserLimit = nonceHistoryPerUserLimit,
                nonceHistoryRetentionMs = nonceHistoryRetentionMs,
                nonceHistoryCleanupIntervalMs = nonceHistoryCleanupIntervalMs,
                activeSessionsPerUserLimit = activeSessionsPerUserLimit,
            ),
            passwordHasher = Pbkdf2PasswordHasher(passwordIterations),
            clock = clock,
            workExecutor = workExecutor,
            usernameFailureLimiter = usernameFailureLimiter,
            syncUserRateLimiter = syncUserRateLimiter,
            registrationPolicy = registrationPolicy,
        )

        private fun create(
            store: AccountStore,
            passwordHasher: PasswordHasher,
            clock: () -> Long = System::currentTimeMillis,
            workExecutor: AccountWorkExecutor,
            usernameFailureLimiter: UsernameFailureLimiter,
            syncUserRateLimiter: AccountRateLimiter,
            registrationPolicy: AccountRegistrationPolicy,
        ): AccountBackend = AccountBackend(
            service = AccountService(
                store = store,
                passwordHasher = passwordHasher,
                clock = clock,
                usernameFailureLimiter = usernameFailureLimiter,
                syncUserRateLimiter = syncUserRateLimiter,
                registrationPolicy = registrationPolicy,
            ),
            store = store,
            workExecutor = workExecutor,
        )
    }
}

internal class AccountService(
    private val store: AccountStore,
    private val passwordHasher: PasswordHasher,
    private val tokenFactory: SessionTokenFactory = SessionTokenFactory(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val usernameFailureLimiter: UsernameFailureLimiter,
    private val syncUserRateLimiter: AccountRateLimiter,
    private val registrationPolicy: AccountRegistrationPolicy,
    private val accessTtlMs: Long = DEFAULT_ACCESS_TTL_MS,
    private val refreshTtlMs: Long = DEFAULT_REFRESH_TTL_MS,
) {
    fun register(request: RegisterRequest): AuthResponse {
        if (!registrationPolicy.enabled) registrationClosed()
        val username = validateRegistrationUsername(request.username)
        val normalizedUsername = username.lowercase(Locale.ROOT)
        when (
            store.registrationAvailability(
                normalizedUsername,
                registrationPolicy.maxUsers,
            )
        ) {
            RegistrationAvailability.Available -> Unit
            RegistrationAvailability.UsernameUnavailable -> usernameUnavailable()
            RegistrationAvailability.Closed -> registrationClosed()
        }
        validateRegistrationPassword(request.password)
        val nickname = request.nickname?.let(::validateNickname) ?: username
        val avatarId = request.avatarId?.let(::validateAvatarId)
            ?: ((normalizedUsername.hashCode() and Int.MAX_VALUE) % AVATAR_COUNT)
        val now = clock()
        val user = StoredUser(
            id = UUID.randomUUID().toString(),
            username = username,
            normalizedUsername = normalizedUsername,
            nickname = nickname,
            avatarId = avatarId,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        val digest = passwordHasher.hash(request.password)
        val issued = issueSession(now)
        val credentials = StoredCredentials(
            user = user,
            passwordSalt = digest.salt,
            passwordHash = digest.hash,
            passwordIterations = digest.iterations,
        )
        val result = try {
            store.createUserWithSession(
                credentials,
                issued.asNewSession(user.id),
                registrationPolicy.maxUsers,
            )
        } finally {
            digest.salt.fill(0)
            digest.hash.fill(0)
        }
        when (result) {
            RegistrationWriteResult.Created -> Unit
            RegistrationWriteResult.UsernameUnavailable -> usernameUnavailable()
            RegistrationWriteResult.Closed -> registrationClosed()
        }
        usernameFailureLimiter.clear(normalizedUsername)
        return issued.toResponse(user)
    }

    fun login(request: LoginRequest): AuthResponse {
        val normalizedUsername = normalizeLoginUsername(request.username)
            ?: invalidCredentials()
        enforceRateLimit(usernameFailureLimiter.checkOrReserve(normalizedUsername))
        if (!isPlausiblePassword(request.password)) {
            usernameFailureLimiter.recordFailure(normalizedUsername)
            invalidCredentials()
        }
        val credentials = store.findUserByNormalizedUsername(normalizedUsername)
        if (credentials == null) {
            // Burn the same password KDF class of work as an existing-user login. The result
            // is deliberately discarded so username existence is not exposed by timing.
            passwordHasher.hash(request.password).wipe()
            usernameFailureLimiter.recordFailure(normalizedUsername)
            invalidCredentials()
        }
        val verified = try {
            passwordHasher.verify(
                request.password,
                PasswordDigest(
                    salt = credentials.passwordSalt,
                    hash = credentials.passwordHash,
                    iterations = credentials.passwordIterations,
                ),
            )
        } finally {
            credentials.passwordSalt.fill(0)
            credentials.passwordHash.fill(0)
        }
        if (!verified) {
            usernameFailureLimiter.recordFailure(normalizedUsername)
            invalidCredentials()
        }

        val now = clock()
        val issued = issueSession(now)
        store.createSession(issued.asNewSession(credentials.user.id))
        usernameFailureLimiter.clear(normalizedUsername)
        return issued.toResponse(credentials.user)
    }

    fun refresh(request: RefreshRequest): AuthResponse {
        val rawToken = request.refreshToken
        if (!tokenFactory.isWellFormed(rawToken)) unauthorized()
        val now = clock()
        val issued = issueSession(now)
        val session = store.rotateSessionByRefreshHash(
            currentRefreshHash = tokenFactory.digest(rawToken),
            replacement = issued.asReplacement(),
            nowEpochMs = now,
        ) ?: unauthorized()
        return issued.toResponse(session.user)
    }

    fun logout(accessToken: String) {
        if (!tokenFactory.isWellFormed(accessToken)) unauthorized()
        if (!store.revokeSessionByAccessHash(tokenFactory.digest(accessToken), clock())) {
            unauthorized()
        }
    }

    fun getProfile(accessToken: String): UserResponse = authenticate(accessToken).user.toResponse()

    fun updateProfile(accessToken: String, request: UpdateProfileRequest): UserResponse {
        if (request.nickname == null && request.avatarId == null) {
            invalidRequest("profile_empty", "至少提供一个资料字段")
        }
        val current = authenticate(accessToken).user
        val nickname = request.nickname?.let(::validateNickname) ?: current.nickname
        val avatarId = request.avatarId?.let(::validateAvatarId) ?: current.avatarId
        return store.updateProfile(current.id, nickname, avatarId, clock())
            ?.toResponse()
            ?: unauthorized()
    }

    fun changePassword(accessToken: String, request: ChangePasswordRequest): AuthResponse {
        val authenticated = authenticate(accessToken)
        val user = authenticated.user
        enforceRateLimit(
            syncUserRateLimiter.check(user.id, AccountRateLimitBucket.PasswordChange),
        )
        if (!isPlausiblePassword(request.currentPassword)) currentPasswordInvalid()
        validateRegistrationPassword(request.newPassword)
        if (request.currentPassword == request.newPassword) {
            invalidRequest("password_unchanged", "新密码不能与当前密码相同")
        }
        if (request.expectedSyncVersion !in 0 until Long.MAX_VALUE) {
            invalidRequest("sync_version_invalid", "同步版本无效")
        }
        if (request.keyVersion !in 1..MAX_KEY_VERSION) {
            invalidRequest("sync_key_version_invalid", "同步密钥版本无效")
        }
        val replacementWrap = decodePasswordChangeWrap(request)
        val credentials = store.findCredentialsByUserId(user.id) ?: unauthorized()
        val expectedDigest = PasswordDigest(
            salt = credentials.passwordSalt,
            hash = credentials.passwordHash,
            iterations = credentials.passwordIterations,
        )
        val verified = try {
            passwordHasher.verify(request.currentPassword, expectedDigest)
        } catch (failure: Throwable) {
            expectedDigest.wipe()
            throw failure
        }
        if (!verified) {
            expectedDigest.wipe()
            currentPasswordInvalid()
        }

        val now = clock()
        val replacementDigest = try {
            passwordHasher.hash(request.newPassword)
        } catch (failure: Throwable) {
            expectedDigest.wipe()
            throw failure
        }
        val issued = issueSession(now)
        val result = try {
            store.changePasswordAndWrapper(
                userId = user.id,
                expectedCurrent = expectedDigest,
                replacement = replacementDigest,
                expectedSyncVersion = request.expectedSyncVersion,
                replacementWrap = replacementWrap,
                replacementSession = issued.asNewSession(user.id),
                updatedAtEpochMs = now,
            )
        } finally {
            expectedDigest.wipe()
            replacementDigest.wipe()
        }
        return when (result) {
            PasswordChangeWriteResult.Changed -> {
                usernameFailureLimiter.clear(user.normalizedUsername)
                issued.toResponse(user.copy(updatedAtEpochMs = now))
            }
            is PasswordChangeWriteResult.VersionConflict -> throw AccountServiceException(
                problem = AccountProblem.VersionConflict,
                safeCode = "sync_version_conflict",
                safeMessage = "同步数据已被其他设备更新",
                currentVersion = result.currentVersion,
            )
            is PasswordChangeWriteResult.KeyVersionConflict -> throw AccountServiceException(
                problem = AccountProblem.VersionConflict,
                safeCode = "sync_key_version_conflict",
                safeMessage = "同步密钥版本已变化",
                currentVersion = result.currentVersion,
            )
            PasswordChangeWriteResult.CredentialsChanged -> currentPasswordInvalid()
        }
    }

    fun getSync(accessToken: String): SyncResponse {
        val user = authenticate(accessToken).user
        enforceRateLimit(syncUserRateLimiter.check(user.id, AccountRateLimitBucket.SyncRead))
        return store.getSyncState(user.id).toResponse()
    }

    fun putSync(accessToken: String, request: PutSyncRequest): SyncResponse {
        val authenticated = authenticate(accessToken)
        val user = authenticated.user
        enforceRateLimit(syncUserRateLimiter.check(user.id, AccountRateLimitBucket.SyncWrite))
        if (request.baseVersion !in 0 until Long.MAX_VALUE) {
            invalidRequest("sync_version_invalid", "同步版本无效")
        }
        val current = store.getSyncState(user.id)
        if (current.version != request.baseVersion) {
            throw AccountServiceException(
                problem = AccountProblem.VersionConflict,
                safeCode = "sync_version_conflict",
                safeMessage = "同步数据已被其他设备更新",
                currentVersion = current.version,
            )
        }
        val decodedEnvelope = decodeEnvelope(request.payload)
        if (
            current.record != null &&
            decodedEnvelope.schemaVersion < current.record.schemaVersion
        ) {
            invalidRequest("sync_schema_downgrade", "同步密文版本不能降级")
        }
        val envelope = resolveKeyWrap(decodedEnvelope, current.record)
        val now = clock()
        val record = StoredSyncRecord(
            userId = user.id,
            version = request.baseVersion + 1L,
            schemaVersion = envelope.schemaVersion,
            algorithm = envelope.algorithm,
            keyVersion = envelope.keyVersion,
            nonce = envelope.nonce,
            ciphertext = envelope.ciphertext,
            wrapVersion = envelope.wrapVersion,
            wrapKdf = envelope.wrapKdf,
            wrapIterations = envelope.wrapIterations,
            wrappedVaultKey = envelope.wrappedVaultKey,
            wrapSalt = envelope.wrapSalt,
            wrapNonce = envelope.wrapNonce,
            updatedAtEpochMs = now,
        )
        return when (
            val result = store.putSyncRecord(
                record = record,
                baseVersion = request.baseVersion,
                authenticatedSessionId = authenticated.sessionId,
                nowEpochMs = now,
            )
        ) {
            is SyncWriteResult.Saved -> result.record.toResponse()
            is SyncWriteResult.VersionConflict -> throw AccountServiceException(
                problem = AccountProblem.VersionConflict,
                safeCode = "sync_version_conflict",
                safeMessage = "同步数据已被其他设备更新",
                currentVersion = result.currentVersion,
            )
            SyncWriteResult.NonceReused -> throw AccountServiceException(
                problem = AccountProblem.NonceReused,
                safeCode = "sync_nonce_reused",
                safeMessage = "同一密钥版本不能重复使用 nonce",
            )
            SyncWriteResult.SessionInvalid -> unauthorized()
        }
    }

    fun deleteSync(accessToken: String): SyncResponse {
        val authenticated = authenticate(accessToken)
        val user = authenticated.user
        enforceRateLimit(syncUserRateLimiter.check(user.id, AccountRateLimitBucket.SyncWrite))
        return when (
            val result = store.deleteSyncData(
                userId = user.id,
                authenticatedSessionId = authenticated.sessionId,
                updatedAtEpochMs = clock(),
            )
        ) {
            is SyncDeleteResult.Deleted -> result.state.toResponse()
            SyncDeleteResult.SessionInvalid -> unauthorized()
        }
    }

    private fun authenticate(rawToken: String): AuthenticatedSession {
        if (!tokenFactory.isWellFormed(rawToken)) unauthorized()
        return store.findActiveSessionByAccessHash(tokenFactory.digest(rawToken), clock())
            ?: unauthorized()
    }

    private fun issueSession(nowEpochMs: Long): IssuedSession = IssuedSession(
        id = UUID.randomUUID().toString(),
        access = tokenFactory.issue(),
        refresh = tokenFactory.issue(),
        accessExpiresAtEpochMs = nowEpochMs + accessTtlMs,
        refreshExpiresAtEpochMs = nowEpochMs + refreshTtlMs,
        createdAtEpochMs = nowEpochMs,
    )

    private fun decodeEnvelope(payload: EncryptedSyncEnvelope): DecodedSyncEnvelope {
        if (payload.schemaVersion != SYNC_SCHEMA_VERSION) {
            invalidRequest("sync_schema_unsupported", "不支持的同步密文版本")
        }
        if (payload.algorithm != SYNC_ALGORITHM) {
            invalidRequest("sync_algorithm_unsupported", "不支持的同步加密算法")
        }
        if (payload.keyVersion !in 1..MAX_KEY_VERSION) {
            invalidRequest("sync_key_version_invalid", "同步密钥版本无效")
        }
        val wrappedFields = listOf(
            payload.wrapVersion,
            payload.wrapKdf,
            payload.wrapIterations,
            payload.wrappedVaultKey,
            payload.wrapSalt,
            payload.wrapNonce,
        )
        if (wrappedFields.any { it != null } && wrappedFields.any { it == null }) {
            invalidRequest("sync_key_wrap_incomplete", "密钥包裹字段必须同时提供")
        }
        if (payload.wrapVersion != null) {
            validateKeyWrapMetadata(
                payload.wrapVersion,
                checkNotNull(payload.wrapKdf),
                checkNotNull(payload.wrapIterations),
            )
        }
        return DecodedSyncEnvelope(
            schemaVersion = payload.schemaVersion,
            algorithm = payload.algorithm,
            keyVersion = payload.keyVersion,
            nonce = decodeBase64Url("nonce", payload.nonce, NONCE_BYTES, NONCE_BYTES),
            ciphertext = decodeBase64Url(
                "ciphertext",
                payload.ciphertext,
                GCM_TAG_BYTES,
                AccountLimits.MAX_CIPHERTEXT_BYTES,
            ),
            wrapVersion = payload.wrapVersion,
            wrapKdf = payload.wrapKdf,
            wrapIterations = payload.wrapIterations,
            wrappedVaultKey = payload.wrappedVaultKey?.let {
                decodeBase64Url(
                    "wrappedVaultKey",
                    it,
                    WRAPPED_VAULT_KEY_BYTES,
                    WRAPPED_VAULT_KEY_BYTES,
                )
            },
            wrapSalt = payload.wrapSalt?.let {
                decodeBase64Url("wrapSalt", it, WRAP_SALT_BYTES, WRAP_SALT_BYTES)
            },
            wrapNonce = payload.wrapNonce?.let {
                decodeBase64Url("wrapNonce", it, NONCE_BYTES, NONCE_BYTES)
            },
        )
    }

    private fun decodePasswordChangeWrap(request: ChangePasswordRequest): StoredKeyWrap {
        validateKeyWrapMetadata(
            request.wrapVersion,
            request.wrapKdf,
            request.wrapIterations,
        )
        return StoredKeyWrap(
            keyVersion = request.keyVersion,
            wrapVersion = request.wrapVersion,
            wrapKdf = request.wrapKdf,
            wrapIterations = request.wrapIterations,
            wrappedVaultKey = decodeBase64Url(
                "wrappedVaultKey",
                request.wrappedVaultKey,
                WRAPPED_VAULT_KEY_BYTES,
                WRAPPED_VAULT_KEY_BYTES,
            ),
            wrapSalt = decodeBase64Url(
                "wrapSalt",
                request.wrapSalt,
                WRAP_SALT_BYTES,
                WRAP_SALT_BYTES,
            ),
            wrapNonce = decodeBase64Url(
                "wrapNonce",
                request.wrapNonce,
                NONCE_BYTES,
                NONCE_BYTES,
            ),
        )
    }

    private fun validateKeyWrapMetadata(version: Int, kdf: String, iterations: Int) {
        if (version != WRAP_VERSION) {
            invalidRequest("sync_wrap_version_unsupported", "不支持的密钥包裹版本")
        }
        if (kdf != WRAP_KDF) {
            invalidRequest("sync_wrap_kdf_unsupported", "不支持的密钥派生算法")
        }
        if (iterations !in MIN_WRAP_ITERATIONS..MAX_WRAP_ITERATIONS) {
            invalidRequest("sync_wrap_iterations_invalid", "密钥派生迭代次数无效")
        }
    }

    private fun resolveKeyWrap(
        envelope: DecodedSyncEnvelope,
        current: StoredSyncRecord?,
    ): DecodedSyncEnvelope {
        val uploadHasWrapper = envelope.wrapVersion != null
        if (current == null) {
            if (!uploadHasWrapper) keyWrapRequired()
            return envelope
        }
        if (envelope.keyVersion != current.keyVersion) {
            if (!uploadHasWrapper) keyWrapRequired()
            return envelope
        }
        if (uploadHasWrapper) return envelope
        if (!current.hasCompleteKeyWrap()) keyWrapRequired()
        return envelope.copy(
            wrapVersion = current.wrapVersion,
            wrapKdf = current.wrapKdf,
            wrapIterations = current.wrapIterations,
            wrappedVaultKey = current.wrappedVaultKey,
            wrapSalt = current.wrapSalt,
            wrapNonce = current.wrapNonce,
        )
    }

    private fun decodeBase64Url(field: String, raw: String, minBytes: Int, maxBytes: Int): ByteArray {
        if (raw.isEmpty() || raw.length > encodedLengthUpperBound(maxBytes)) {
            invalidRequest("sync_envelope_invalid", "$field 长度无效")
        }
        if (!BASE64_URL_PATTERN.matches(raw)) {
            invalidRequest("sync_envelope_invalid", "$field 必须是无填充 Base64URL")
        }
        val decoded = runCatching { base64Decoder.decode(raw) }.getOrNull()
            ?: invalidRequest("sync_envelope_invalid", "$field 编码无效")
        if (decoded.size !in minBytes..maxBytes || base64Encoder.encodeToString(decoded) != raw) {
            decoded.fill(0)
            invalidRequest("sync_envelope_invalid", "$field 长度或编码无效")
        }
        return decoded
    }

    private fun encodedLengthUpperBound(bytes: Int): Int = (bytes * 4 + 2) / 3

    private fun validateRegistrationUsername(raw: String): String {
        val value = raw.trim()
        if (!USERNAME_PATTERN.matches(value)) {
            invalidRequest("username_invalid", "用户名需为 3–40 位字母、数字、点、下划线或连字符")
        }
        return value
    }

    private fun normalizeLoginUsername(raw: String): String? = raw.trim()
        .takeIf(USERNAME_PATTERN::matches)
        ?.lowercase(Locale.ROOT)

    private fun validateRegistrationPassword(password: String) {
        if (!isPlausiblePassword(password) || password.codePointCount(0, password.length) < MIN_PASSWORD_CHARS) {
            invalidRequest("password_invalid", "密码至少 10 个字符，且不能包含控制字符")
        }
    }

    private fun isPlausiblePassword(password: String): Boolean {
        val codePoints = password.codePointCount(0, password.length)
        return codePoints in 1..MAX_PASSWORD_CHARS &&
            password.toByteArray(Charsets.UTF_8).size <= MAX_PASSWORD_BYTES &&
            password.none { it == '\u0000' || Character.isISOControl(it) }
    }

    private fun validateNickname(raw: String): String {
        val value = raw.trim()
        val graphemes = GRAPHEME_REGEX.findAll(value).count()
        if (
            graphemes !in 1..MAX_NICKNAME_GRAPHEMES ||
            value.toByteArray(Charsets.UTF_8).size > MAX_NICKNAME_BYTES ||
            value.any { Character.isISOControl(it) }
        ) {
            invalidRequest("nickname_invalid", "昵称需为 1–24 个字符且不能包含控制字符")
        }
        return value
    }

    private fun validateAvatarId(value: Int): Int {
        if (value !in 0 until AVATAR_COUNT) {
            invalidRequest("avatar_invalid", "头像编号无效")
        }
        return value
    }

    private fun enforceRateLimit(decision: RateLimitDecision) {
        if (decision is RateLimitDecision.Limited) {
            throw AccountServiceException(
                problem = AccountProblem.RateLimited,
                safeCode = "rate_limited",
                safeMessage = "尝试次数过多，请稍后再试",
                retryAfterSeconds = decision.retryAfterSeconds,
            )
        }
    }

    private fun keyWrapRequired(): Nothing = invalidRequest(
        "sync_key_wrap_required",
        "首次同步或更换密钥版本时必须提供完整密钥包裹信息",
    )

    private fun invalidCredentials(): Nothing = throw AccountServiceException(
        problem = AccountProblem.InvalidCredentials,
        safeCode = "invalid_credentials",
        safeMessage = "用户名或密码错误",
    )

    private fun usernameUnavailable(): Nothing = throw AccountServiceException(
        problem = AccountProblem.UsernameUnavailable,
        safeCode = "username_unavailable",
        safeMessage = "用户名不可用",
    )

    private fun registrationClosed(): Nothing = throw AccountServiceException(
        problem = AccountProblem.RegistrationClosed,
        safeCode = "registration_closed",
        safeMessage = "暂不开放新账号注册",
    )

    private fun currentPasswordInvalid(): Nothing = throw AccountServiceException(
        problem = AccountProblem.CurrentPasswordInvalid,
        safeCode = "current_password_invalid",
        safeMessage = "当前密码不正确",
    )

    private fun unauthorized(): Nothing = throw AccountServiceException(
        problem = AccountProblem.Unauthorized,
        safeCode = "unauthorized",
        safeMessage = "登录状态无效或已过期",
    )

    private fun invalidRequest(code: String, message: String): Nothing = throw AccountServiceException(
        problem = AccountProblem.InvalidRequest,
        safeCode = code,
        safeMessage = message,
    )

    private data class IssuedSession(
        val id: String,
        val access: IssuedToken,
        val refresh: IssuedToken,
        val accessExpiresAtEpochMs: Long,
        val refreshExpiresAtEpochMs: Long,
        val createdAtEpochMs: Long,
    ) {
        fun asNewSession(userId: String): NewSession = NewSession(
            id = id,
            userId = userId,
            accessTokenHash = access.hash,
            refreshTokenHash = refresh.hash,
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
            createdAtEpochMs = createdAtEpochMs,
        )

        fun asReplacement(): SessionReplacement = SessionReplacement(
            id = id,
            accessTokenHash = access.hash,
            refreshTokenHash = refresh.hash,
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
            createdAtEpochMs = createdAtEpochMs,
        )

        fun toResponse(user: StoredUser): AuthResponse = AuthResponse(
            user = user.toResponse(),
            accessToken = access.plaintext,
            accessExpiresAtEpochMs = accessExpiresAtEpochMs,
            refreshToken = refresh.plaintext,
            refreshExpiresAtEpochMs = refreshExpiresAtEpochMs,
        )
    }

    private data class DecodedSyncEnvelope(
        val schemaVersion: Int,
        val algorithm: String,
        val keyVersion: Int,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val wrapVersion: Int?,
        val wrapKdf: String?,
        val wrapIterations: Int?,
        val wrappedVaultKey: ByteArray?,
        val wrapSalt: ByteArray?,
        val wrapNonce: ByteArray?,
    )

    companion object {
        private const val DEFAULT_ACCESS_TTL_MS = 15 * 60_000L
        private const val DEFAULT_REFRESH_TTL_MS = 30L * 24 * 60 * 60_000L
        private const val MIN_PASSWORD_CHARS = 10
        private const val MAX_PASSWORD_CHARS = 128
        private const val MAX_PASSWORD_BYTES = 512
        private const val MAX_NICKNAME_GRAPHEMES = 24
        private const val MAX_NICKNAME_BYTES = 128
        private const val AVATAR_COUNT = 8
        private const val SYNC_SCHEMA_VERSION = 1
        private const val SYNC_ALGORITHM = "AES-256-GCM"
        private const val MAX_KEY_VERSION = 1_000_000
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BYTES = 16
        private const val WRAPPED_VAULT_KEY_BYTES = 32 + GCM_TAG_BYTES
        private const val WRAP_SALT_BYTES = 16
        private const val WRAP_VERSION = 1
        private const val WRAP_KDF = "PBKDF2-HMAC-SHA256"
        private const val MIN_WRAP_ITERATIONS = 100_000
        private const val MAX_WRAP_ITERATIONS = 2_000_000
        private val USERNAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,39}")
        private val GRAPHEME_REGEX = Regex("\\X")
        private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        private val base64Decoder = Base64.getUrlDecoder()
        private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}

private fun StoredUser.toResponse(): UserResponse = UserResponse(
    id = id,
    username = username,
    nickname = nickname,
    avatarId = avatarId,
    createdAtEpochMs = createdAtEpochMs,
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun StoredSyncRecord.toResponse(): SyncResponse = SyncResponse(
    version = version,
    payload = EncryptedSyncEnvelope(
        schemaVersion = schemaVersion,
        algorithm = algorithm,
        keyVersion = keyVersion,
        nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
        ciphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
        wrapVersion = wrapVersion,
        wrapKdf = wrapKdf,
        wrapIterations = wrapIterations,
        wrappedVaultKey = wrappedVaultKey?.let(Base64.getUrlEncoder().withoutPadding()::encodeToString),
        wrapSalt = wrapSalt?.let(Base64.getUrlEncoder().withoutPadding()::encodeToString),
        wrapNonce = wrapNonce?.let(Base64.getUrlEncoder().withoutPadding()::encodeToString),
    ),
    updatedAtEpochMs = updatedAtEpochMs,
)

private fun StoredSyncState.toResponse(): SyncResponse =
    record?.toResponse() ?: SyncResponse(
        version = version,
        updatedAtEpochMs = updatedAtEpochMs,
    )

private fun StoredSyncRecord.hasCompleteKeyWrap(): Boolean =
    wrapVersion != null &&
        wrapKdf != null &&
        wrapIterations != null &&
        wrappedVaultKey != null &&
        wrapSalt != null &&
        wrapNonce != null

private fun PasswordDigest.wipe() {
    salt.fill(0)
    hash.fill(0)
}
