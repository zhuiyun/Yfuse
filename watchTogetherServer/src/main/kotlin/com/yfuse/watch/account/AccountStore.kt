package com.yfuse.watch.account

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

internal interface AccountStore : AutoCloseable {
    fun registrationAvailability(
        normalizedUsername: String,
        maxUsers: Int,
    ): RegistrationAvailability

    fun createUserWithSession(
        credentials: StoredCredentials,
        session: NewSession,
        maxUsers: Int,
        invitationDigest: ByteArray? = null,
        invitationKind: InvitationKind? = null,
    ): RegistrationWriteResult

    fun issueInvite(
        authenticatedSessionId: String,
        invite: NewIssuedInvite,
        nowEpochMs: Long,
    ): InviteIssueWriteResult

    fun permissionsForUser(userId: String): Set<String>

    fun synchronizeInviteIssuerPermissions(
        normalizedUsernames: Set<String>,
        nowEpochMs: Long,
    )

    fun findUserByNormalizedUsername(normalizedUsername: String): StoredCredentials?

    fun findCredentialsByUserId(userId: String): StoredCredentials?

    fun createSession(session: NewSession)

    fun findActiveSessionByAccessHash(
        tokenHash: ByteArray,
        nowEpochMs: Long,
        touchLastSeen: Boolean,
    ): AuthenticatedSession?

    fun rotateSessionByRefreshHash(
        currentRefreshHash: ByteArray,
        replacement: SessionReplacement,
        nowEpochMs: Long,
    ): AuthenticatedSession?

    fun revokeSessionByAccessHash(
        tokenHash: ByteArray,
        nowEpochMs: Long,
    ): Boolean

    fun listActiveSessions(
        userId: String,
        nowEpochMs: Long,
    ): List<StoredSession>

    fun revokeSession(
        userId: String,
        sessionId: String,
        nowEpochMs: Long,
    ): Boolean

    fun revokeOtherSessions(
        userId: String,
        currentSessionId: String,
        nowEpochMs: Long,
    ): Int

    fun revokeAllSessions(
        userId: String,
        nowEpochMs: Long,
    ): Int

    fun deleteUser(
        userId: String,
        expectedCurrent: PasswordDigest,
        authenticatedSessionId: String,
        nowEpochMs: Long,
    ): DeleteAccountWriteResult

    fun updateProfile(
        userId: String,
        nickname: String,
        avatarId: Int,
        updatedAtEpochMs: Long,
    ): StoredUser?

    fun getSyncState(userId: String): StoredSyncState

    fun putSyncRecord(
        record: StoredSyncRecord,
        baseVersion: Long,
        authenticatedSessionId: String,
        nowEpochMs: Long,
    ): SyncWriteResult

    fun deleteSyncData(
        userId: String,
        authenticatedSessionId: String,
        updatedAtEpochMs: Long,
    ): SyncDeleteResult

    fun changePasswordAndWrapper(
        userId: String,
        expectedCurrent: PasswordDigest,
        replacement: PasswordDigest,
        expectedSyncVersion: Long,
        replacementWrap: StoredKeyWrap,
        replacementSession: NewSession,
        updatedAtEpochMs: Long,
    ): PasswordChangeWriteResult
}

/**
 * A deliberately small JDBC store. One connection is serialized behind [lock], which is
 * appropriate for the low-write account/sync workload and avoids pretending a JDBC
 * connection is coroutine/thread safe. Every multi-statement mutation is transactional.
 */
internal class SqliteAccountStore private constructor(
    private val connection: Connection,
    private val fileBacked: Boolean,
    private val nonceHistoryPerUserLimit: Int,
    private val nonceHistoryRetentionMs: Long,
    private val nonceHistoryCleanupIntervalMs: Long,
    private val activeSessionsPerUserLimit: Int,
) : AccountStore {
    private val lock = Any()
    private var nextNonceHistoryCleanupAtEpochMs = Long.MIN_VALUE
    private var lastNonceHistoryClockEpochMs = Long.MIN_VALUE

    init {
        require(nonceHistoryPerUserLimit > 0)
        require(nonceHistoryRetentionMs > 0L)
        require(nonceHistoryCleanupIntervalMs > 0L)
        require(activeSessionsPerUserLimit > 0)
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
                if (fileBacked) {
                    // WAL lets readers proceed while the serialized account writer commits and
                    // is also the format expected by the production snapshot/backup procedure.
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute("PRAGMA synchronous = FULL")
                    statement.execute("PRAGMA wal_autocheckpoint = 1000")
                }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS users (
                        id TEXT PRIMARY KEY,
                        username TEXT NOT NULL,
                        username_normalized TEXT NOT NULL UNIQUE,
                        password_salt BLOB NOT NULL CHECK(length(password_salt) = 16),
                        password_hash BLOB NOT NULL CHECK(length(password_hash) = 32),
                        password_iterations INTEGER NOT NULL CHECK(password_iterations > 0),
                        nickname TEXT NOT NULL,
                        avatar_id INTEGER NOT NULL,
                        created_at_ms INTEGER NOT NULL,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS account_invite_redemptions (
                        invite_hash BLOB PRIMARY KEY CHECK(length(invite_hash) = 32),
                        user_id TEXT NOT NULL,
                        redeemed_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS account_permissions (
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        permission TEXT NOT NULL,
                        granted_at_ms INTEGER NOT NULL,
                        managed_by_config INTEGER NOT NULL DEFAULT 0 CHECK(managed_by_config IN (0, 1)),
                        PRIMARY KEY(user_id, permission)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS account_invites (
                        invite_hash BLOB PRIMARY KEY CHECK(length(invite_hash) = 32),
                        issuer_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        created_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        redeemed_by_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                        redeemed_at_ms INTEGER,
                        revoked_at_ms INTEGER,
                        CHECK(expires_at_ms > created_at_ms),
                        CHECK(redeemed_at_ms IS NOT NULL OR redeemed_by_user_id IS NULL)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS account_invites_issuer_idx
                    ON account_invites(issuer_user_id, created_at_ms)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        access_token_hash BLOB NOT NULL UNIQUE CHECK(length(access_token_hash) = 32),
                        refresh_token_hash BLOB NOT NULL UNIQUE CHECK(length(refresh_token_hash) = 32),
                        access_expires_at_ms INTEGER NOT NULL,
                        refresh_expires_at_ms INTEGER NOT NULL,
                        revoked_at_ms INTEGER,
                        created_at_ms INTEGER NOT NULL,
                        device_name TEXT NOT NULL DEFAULT '未知设备',
                        last_seen_at_ms INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS sessions_user_id_idx
                    ON sessions(user_id)
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_records (
                        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        version INTEGER NOT NULL CHECK(version > 0),
                        schema_version INTEGER NOT NULL,
                        algorithm TEXT NOT NULL,
                        key_version INTEGER NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                        ciphertext BLOB NOT NULL,
                        wrap_version INTEGER,
                        wrap_kdf TEXT,
                        wrap_iterations INTEGER,
                        wrapped_vault_key BLOB,
                        wrap_salt BLOB,
                        wrap_nonce BLOB,
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_nonce_history (
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        key_version INTEGER NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                        recorded_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(user_id, key_version, nonce)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_revisions (
                        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        version INTEGER NOT NULL CHECK(version > 0),
                        updated_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
            migrateInviteRedemptionAuthorityLocked()
            ensureColumnLocked("sync_records", "wrap_version", "INTEGER")
            ensureColumnLocked("sync_records", "wrap_kdf", "TEXT")
            ensureColumnLocked("sync_records", "wrap_iterations", "INTEGER")
            ensureColumnLocked(
                "sync_nonce_history",
                "recorded_at_ms",
                "INTEGER NOT NULL DEFAULT 0",
            )
            ensureColumnLocked("sessions", "device_name", "TEXT NOT NULL DEFAULT '未知设备'")
            ensureColumnLocked("sessions", "last_seen_at_ms", "INTEGER NOT NULL DEFAULT 0")
            ensureColumnLocked(
                "account_permissions",
                "managed_by_config",
                "INTEGER NOT NULL DEFAULT 0",
            )
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "UPDATE sessions SET last_seen_at_ms = created_at_ms WHERE last_seen_at_ms = 0",
                )
            }
            val migrationNowEpochMs = System.currentTimeMillis()
            connection
                .prepareStatement(
                    """
                    UPDATE sync_records
                    SET wrap_version = 1,
                        wrap_kdf = 'PBKDF2-HMAC-SHA256',
                        wrap_iterations = 600000
                    WHERE wrapped_vault_key IS NOT NULL
                      AND wrap_salt IS NOT NULL
                      AND wrap_nonce IS NOT NULL
                      AND (wrap_version IS NULL OR wrap_kdf IS NULL OR wrap_iterations IS NULL)
                    """.trimIndent(),
                ).use { it.executeUpdate() }
            connection
                .prepareStatement(
                    "UPDATE sync_nonce_history SET recorded_at_ms = ? WHERE recorded_at_ms = 0",
                ).use { statement ->
                    statement.setLong(1, migrationNowEpochMs)
                    statement.executeUpdate()
                }
            // Existing databases used sync_records.version as the only revision. Seed the
            // separate revision row once so later deletes can retain a tombstone without
            // making the legacy payload columns nullable.
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT OR IGNORE INTO sync_revisions(user_id, version, updated_at_ms)
                    SELECT s.user_id, s.version, s.updated_at_ms
                    FROM sync_records s
                    JOIN users u ON u.id = s.user_id
                    """.trimIndent(),
                )
            }
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS sync_nonce_recorded_at_idx
                    ON sync_nonce_history(recorded_at_ms)
                    """.trimIndent(),
                )
            }
        }
    }

    override fun registrationAvailability(
        normalizedUsername: String,
        maxUsers: Int,
    ): RegistrationAvailability =
        synchronized(lock) {
            when {
                userCountLocked() >= maxUsers -> RegistrationAvailability.Closed
                findUserByNormalizedUsernameLocked(normalizedUsername) != null ->
                    RegistrationAvailability.UsernameUnavailable
                else -> RegistrationAvailability.Available
            }
        }

    override fun createUserWithSession(
        credentials: StoredCredentials,
        session: NewSession,
        maxUsers: Int,
        invitationDigest: ByteArray?,
        invitationKind: InvitationKind?,
    ): RegistrationWriteResult =
        synchronized(lock) {
            val user = credentials.user
            transaction {
                if (invitationDigest != null && invitationKind == InvitationKind.Issued) {
                    val available =
                        connection
                            .prepareStatement(
                                """
                                SELECT 1 FROM account_invites
                                WHERE invite_hash = ?
                                  AND redeemed_at_ms IS NULL
                                  AND revoked_at_ms IS NULL
                                  AND expires_at_ms > ?
                                LIMIT 1
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setBytes(1, invitationDigest)
                                statement.setLong(2, user.createdAtEpochMs)
                                statement.executeQuery().use(ResultSet::next)
                            }
                    if (!available) return@transaction RegistrationWriteResult.InviteUnavailable
                }
                if (invitationDigest != null && invitationKind == InvitationKind.Static) {
                    val alreadyRedeemed =
                        connection
                            .prepareStatement(
                                "SELECT 1 FROM account_invite_redemptions WHERE invite_hash = ? LIMIT 1",
                            ).use { statement ->
                                statement.setBytes(1, invitationDigest)
                                statement.executeQuery().use(ResultSet::next)
                            }
                    if (alreadyRedeemed) {
                        return@transaction RegistrationWriteResult.InviteUnavailable
                    }
                }
                if (userCountLocked() >= maxUsers) {
                    return@transaction RegistrationWriteResult.Closed
                }
                if (findUserByNormalizedUsernameLocked(user.normalizedUsername) != null) {
                    return@transaction RegistrationWriteResult.UsernameUnavailable
                }
                if (invitationDigest != null && invitationKind == InvitationKind.Static) {
                    connection
                        .prepareStatement(
                            """
                            INSERT INTO account_invite_redemptions(
                                invite_hash, user_id, redeemed_at_ms
                            ) VALUES (?, ?, ?)
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setBytes(1, invitationDigest)
                            statement.setString(2, user.id)
                            statement.setLong(3, user.createdAtEpochMs)
                            check(statement.executeUpdate() == 1)
                        }
                }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO users (
                            id, username, username_normalized, password_salt, password_hash,
                            password_iterations, nickname, avatar_id, created_at_ms, updated_at_ms
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, user.id)
                        statement.setString(2, user.username)
                        statement.setString(3, user.normalizedUsername)
                        statement.setBytes(4, credentials.passwordSalt)
                        statement.setBytes(5, credentials.passwordHash)
                        statement.setInt(6, credentials.passwordIterations)
                        statement.setString(7, user.nickname)
                        statement.setInt(8, user.avatarId)
                        statement.setLong(9, user.createdAtEpochMs)
                        statement.setLong(10, user.updatedAtEpochMs)
                        statement.executeUpdate()
                    }
                if (invitationDigest != null && invitationKind == InvitationKind.Issued) {
                    val consumed =
                        connection
                            .prepareStatement(
                                """
                                UPDATE account_invites
                                SET redeemed_by_user_id = ?, redeemed_at_ms = ?
                                WHERE invite_hash = ?
                                  AND redeemed_at_ms IS NULL
                                  AND revoked_at_ms IS NULL
                                  AND expires_at_ms > ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setString(1, user.id)
                                statement.setLong(2, user.createdAtEpochMs)
                                statement.setBytes(3, invitationDigest)
                                statement.setLong(4, user.createdAtEpochMs)
                                statement.executeUpdate()
                            }
                    check(consumed == 1) { "issued invite changed inside registration transaction" }
                }
                insertSessionLocked(session)
                trimActiveSessionsForUserLocked(
                    session.userId,
                    session.createdAtEpochMs,
                )
                RegistrationWriteResult.Created
            }
        }

    override fun issueInvite(
        authenticatedSessionId: String,
        invite: NewIssuedInvite,
        nowEpochMs: Long,
    ): InviteIssueWriteResult =
        synchronized(lock) {
            transaction {
                val active =
                    connection
                        .prepareStatement(
                            """
                            SELECT 1 FROM sessions
                            WHERE id = ? AND user_id = ? AND revoked_at_ms IS NULL
                              AND access_expires_at_ms > ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, authenticatedSessionId)
                            statement.setString(2, invite.issuerUserId)
                            statement.setLong(3, nowEpochMs)
                            statement.executeQuery().use(ResultSet::next)
                        }
                if (!active) return@transaction InviteIssueWriteResult.SessionInvalid
                val permitted =
                    connection
                        .prepareStatement(
                            """
                            SELECT 1 FROM account_permissions
                            WHERE user_id = ? AND permission = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, invite.issuerUserId)
                            statement.setString(2, INVITE_ISSUE_CAPABILITY)
                            statement.executeQuery().use(ResultSet::next)
                        }
                if (!permitted) return@transaction InviteIssueWriteResult.Forbidden
                connection
                    .prepareStatement(
                        """
                        INSERT INTO account_invites(
                            invite_hash, issuer_user_id, created_at_ms, expires_at_ms
                        ) VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setBytes(1, invite.digest)
                        statement.setString(2, invite.issuerUserId)
                        statement.setLong(3, invite.createdAtEpochMs)
                        statement.setLong(4, invite.expiresAtEpochMs)
                        check(statement.executeUpdate() == 1)
                    }
                InviteIssueWriteResult.Created
            }
        }

    override fun permissionsForUser(userId: String): Set<String> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT permission FROM account_permissions WHERE user_id = ? ORDER BY permission",
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { result ->
                        buildSet { while (result.next()) add(result.getString("permission")) }
                    }
                }
        }

    override fun synchronizeInviteIssuerPermissions(
        normalizedUsernames: Set<String>,
        nowEpochMs: Long,
    ) {
        synchronized(lock) {
            transaction {
                connection
                    .prepareStatement(
                        "DELETE FROM account_permissions WHERE permission = ? AND managed_by_config = 1",
                    ).use { statement ->
                        statement.setString(1, INVITE_ISSUE_CAPABILITY)
                        statement.executeUpdate()
                    }
                normalizedUsernames.forEach { normalizedUsername ->
                    connection
                        .prepareStatement(
                            """
                            INSERT OR IGNORE INTO account_permissions(
                                user_id, permission, granted_at_ms, managed_by_config
                            )
                            SELECT id, ?, ?, 1 FROM users WHERE username_normalized = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, INVITE_ISSUE_CAPABILITY)
                            statement.setLong(2, nowEpochMs)
                            statement.setString(3, normalizedUsername)
                            statement.executeUpdate()
                        }
                }
            }
        }
    }

    override fun findUserByNormalizedUsername(normalizedUsername: String): StoredCredentials? =
        synchronized(lock) { findUserByNormalizedUsernameLocked(normalizedUsername) }

    override fun findCredentialsByUserId(userId: String): StoredCredentials? =
        synchronized(lock) {
            findCredentialsByUserIdLocked(userId)
        }

    override fun createSession(session: NewSession) {
        synchronized(lock) {
            transaction {
                pruneSessionsLocked(session.createdAtEpochMs)
                insertSessionLocked(session)
                trimActiveSessionsForUserLocked(
                    session.userId,
                    session.createdAtEpochMs,
                )
            }
        }
    }

    override fun findActiveSessionByAccessHash(
        tokenHash: ByteArray,
        nowEpochMs: Long,
        touchLastSeen: Boolean,
    ): AuthenticatedSession? =
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    SELECT s.id AS session_id, s.access_expires_at_ms, $USER_COLUMNS
                    FROM sessions s
                    JOIN users u ON u.id = s.user_id
                    WHERE s.access_token_hash = ?
                      AND s.revoked_at_ms IS NULL
                      AND s.access_expires_at_ms > ?
                    LIMIT 1
                    """.trimIndent(),
                ).use { statement ->
                    statement.setBytes(1, tokenHash)
                    statement.setLong(2, nowEpochMs)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            AuthenticatedSession(
                                sessionId = result.getString("session_id"),
                                user = result.readUser(),
                                accessExpiresAtEpochMs = result.getLong("access_expires_at_ms"),
                            ).also {
                                if (touchLastSeen) {
                                    connection
                                        .prepareStatement(
                                            "UPDATE sessions SET last_seen_at_ms = ? WHERE id = ?",
                                        ).use { touch ->
                                            touch.setLong(1, nowEpochMs)
                                            touch.setString(2, it.sessionId)
                                            touch.executeUpdate()
                                        }
                                }
                            }
                        }
                    }
                }
        }

    override fun rotateSessionByRefreshHash(
        currentRefreshHash: ByteArray,
        replacement: SessionReplacement,
        nowEpochMs: Long,
    ): AuthenticatedSession? =
        synchronized(lock) {
            transaction {
                val current =
                    connection
                        .prepareStatement(
                            """
                            SELECT s.id AS session_id, s.access_expires_at_ms, $USER_COLUMNS
                            FROM sessions s
                            JOIN users u ON u.id = s.user_id
                            WHERE s.refresh_token_hash = ?
                              AND s.revoked_at_ms IS NULL
                              AND s.refresh_expires_at_ms > ?
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setBytes(1, currentRefreshHash)
                            statement.setLong(2, nowEpochMs)
                            statement.executeQuery().use { result ->
                                if (!result.next()) {
                                    null
                                } else {
                                    AuthenticatedSession(
                                        sessionId = result.getString("session_id"),
                                        user = result.readUser(),
                                        accessExpiresAtEpochMs = result.getLong("access_expires_at_ms"),
                                    )
                                }
                            }
                        } ?: return@transaction null

                val changed =
                    connection
                        .prepareStatement(
                            """
                            UPDATE sessions
                            SET id = ?, access_token_hash = ?, refresh_token_hash = ?,
                                access_expires_at_ms = ?, refresh_expires_at_ms = ?, created_at_ms = ?,
                                device_name = COALESCE(?, device_name), last_seen_at_ms = ?
                            WHERE id = ? AND refresh_token_hash = ? AND revoked_at_ms IS NULL
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, replacement.id)
                            statement.setBytes(2, replacement.accessTokenHash)
                            statement.setBytes(3, replacement.refreshTokenHash)
                            statement.setLong(4, replacement.accessExpiresAtEpochMs)
                            statement.setLong(5, replacement.refreshExpiresAtEpochMs)
                            statement.setLong(6, replacement.createdAtEpochMs)
                            statement.setString(7, replacement.deviceName)
                            statement.setLong(8, nowEpochMs)
                            statement.setString(9, current.sessionId)
                            statement.setBytes(10, currentRefreshHash)
                            statement.executeUpdate()
                        }
                if (changed != 1) {
                    null
                } else {
                    current.copy(
                        sessionId = replacement.id,
                        accessExpiresAtEpochMs = replacement.accessExpiresAtEpochMs,
                    )
                }
            }
        }

    override fun revokeSessionByAccessHash(
        tokenHash: ByteArray,
        nowEpochMs: Long,
    ): Boolean =
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    UPDATE sessions
                    SET revoked_at_ms = ?
                    WHERE access_token_hash = ? AND revoked_at_ms IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, nowEpochMs)
                    statement.setBytes(2, tokenHash)
                    statement.executeUpdate() == 1
                }
        }

    override fun listActiveSessions(
        userId: String,
        nowEpochMs: Long,
    ): List<StoredSession> =
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    SELECT id, device_name, created_at_ms, last_seen_at_ms
                    FROM sessions
                    WHERE user_id = ? AND revoked_at_ms IS NULL AND refresh_expires_at_ms > ?
                    ORDER BY last_seen_at_ms DESC, created_at_ms DESC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.setLong(2, nowEpochMs)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(
                                    StoredSession(
                                        id = result.getString("id"),
                                        deviceName = result.getString("device_name"),
                                        createdAtEpochMs = result.getLong("created_at_ms"),
                                        lastSeenAtEpochMs = result.getLong("last_seen_at_ms"),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    override fun revokeSession(
        userId: String,
        sessionId: String,
        nowEpochMs: Long,
    ): Boolean =
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    UPDATE sessions SET revoked_at_ms = ?
                    WHERE user_id = ? AND id = ? AND revoked_at_ms IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, nowEpochMs)
                    statement.setString(2, userId)
                    statement.setString(3, sessionId)
                    statement.executeUpdate() == 1
                }
        }

    override fun revokeOtherSessions(
        userId: String,
        currentSessionId: String,
        nowEpochMs: Long,
    ): Int =
        synchronized(lock) {
            connection
                .prepareStatement(
                    """
                    UPDATE sessions SET revoked_at_ms = ?
                    WHERE user_id = ? AND id <> ? AND revoked_at_ms IS NULL
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, nowEpochMs)
                    statement.setString(2, userId)
                    statement.setString(3, currentSessionId)
                    statement.executeUpdate()
                }
        }

    override fun revokeAllSessions(
        userId: String,
        nowEpochMs: Long,
    ): Int =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "UPDATE sessions SET revoked_at_ms = ? WHERE user_id = ? AND revoked_at_ms IS NULL",
                ).use { statement ->
                    statement.setLong(1, nowEpochMs)
                    statement.setString(2, userId)
                    statement.executeUpdate()
                }
        }

    override fun deleteUser(
        userId: String,
        expectedCurrent: PasswordDigest,
        authenticatedSessionId: String,
        nowEpochMs: Long,
    ): DeleteAccountWriteResult =
        synchronized(lock) {
            transaction {
                if (!isActiveSessionLocked(authenticatedSessionId, userId, nowEpochMs)) {
                    return@transaction DeleteAccountWriteResult.SessionInvalid
                }
                val current =
                    findCredentialsByUserIdLocked(userId)
                        ?: return@transaction DeleteAccountWriteResult.SessionInvalid
                val credentialsMatch =
                    try {
                        current.passwordIterations == expectedCurrent.iterations &&
                            current.passwordSalt.contentEquals(expectedCurrent.salt) &&
                            current.passwordHash.contentEquals(expectedCurrent.hash)
                    } finally {
                        current.passwordSalt.fill(0)
                        current.passwordHash.fill(0)
                    }
                if (!credentialsMatch) return@transaction DeleteAccountWriteResult.CredentialsChanged
                connection.prepareStatement("DELETE FROM users WHERE id = ?").use { statement ->
                    statement.setString(1, userId)
                    check(statement.executeUpdate() == 1)
                }
                DeleteAccountWriteResult.Deleted
            }
        }

    override fun updateProfile(
        userId: String,
        nickname: String,
        avatarId: Int,
        updatedAtEpochMs: Long,
    ): StoredUser? =
        synchronized(lock) {
            val changed =
                connection
                    .prepareStatement(
                        """
                        UPDATE users SET nickname = ?, avatar_id = ?, updated_at_ms = ? WHERE id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, nickname)
                        statement.setInt(2, avatarId)
                        statement.setLong(3, updatedAtEpochMs)
                        statement.setString(4, userId)
                        statement.executeUpdate()
                    }
            if (changed != 1) null else findUserByIdLocked(userId)
        }

    override fun getSyncState(userId: String): StoredSyncState =
        synchronized(lock) {
            getSyncStateLocked(userId)
        }

    override fun putSyncRecord(
        record: StoredSyncRecord,
        baseVersion: Long,
        authenticatedSessionId: String,
        nowEpochMs: Long,
    ): SyncWriteResult =
        synchronized(lock) {
            transaction {
                if (
                    !isActiveSessionLocked(
                        sessionId = authenticatedSessionId,
                        userId = record.userId,
                        nowEpochMs = nowEpochMs,
                    )
                ) {
                    return@transaction SyncWriteResult.SessionInvalid
                }
                val current = getSyncStateLocked(record.userId)
                if (current.version != baseVersion) {
                    return@transaction SyncWriteResult.VersionConflict(current.version)
                }
                check(record.version == baseVersion + 1L)

                cleanupNonceHistoryLocked(record.updatedAtEpochMs)
                val nonceSeen =
                    connection
                        .prepareStatement(
                            """
                            SELECT 1 FROM sync_nonce_history
                            WHERE user_id = ? AND key_version = ? AND nonce = ?
                            LIMIT 1
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setString(1, record.userId)
                            statement.setInt(2, record.keyVersion)
                            statement.setBytes(3, record.nonce)
                            statement.executeQuery().use(ResultSet::next)
                        }
                if (nonceSeen) return@transaction SyncWriteResult.NonceReused

                if (current.record == null) {
                    insertSyncRecordLocked(record)
                } else {
                    updateSyncRecordLocked(record)
                }
                upsertSyncRevisionLocked(
                    userId = record.userId,
                    version = record.version,
                    updatedAtEpochMs = record.updatedAtEpochMs,
                )
                connection
                    .prepareStatement(
                        """
                        INSERT INTO sync_nonce_history(user_id, key_version, nonce, recorded_at_ms)
                        VALUES (?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, record.userId)
                        statement.setInt(2, record.keyVersion)
                        statement.setBytes(3, record.nonce)
                        statement.setLong(4, record.updatedAtEpochMs)
                        statement.executeUpdate()
                    }
                trimNonceHistoryForUserLocked(record.userId)
                SyncWriteResult.Saved(record)
            }
        }

    override fun deleteSyncData(
        userId: String,
        authenticatedSessionId: String,
        updatedAtEpochMs: Long,
    ): SyncDeleteResult =
        synchronized(lock) {
            transaction {
                if (
                    !isActiveSessionLocked(
                        sessionId = authenticatedSessionId,
                        userId = userId,
                        nowEpochMs = updatedAtEpochMs,
                    )
                ) {
                    return@transaction SyncDeleteResult.SessionInvalid
                }
                val currentVersion = getSyncStateLocked(userId).version
                check(currentVersion < Long.MAX_VALUE) { "sync revision exhausted" }
                val tombstoneVersion = currentVersion + 1L
                connection
                    .prepareStatement(
                        "DELETE FROM sync_nonce_history WHERE user_id = ?",
                    ).use { statement ->
                        statement.setString(1, userId)
                        statement.executeUpdate()
                    }
                connection
                    .prepareStatement(
                        "DELETE FROM sync_records WHERE user_id = ?",
                    ).use { statement ->
                        statement.setString(1, userId)
                        statement.executeUpdate()
                    }
                upsertSyncRevisionLocked(userId, tombstoneVersion, updatedAtEpochMs)
                SyncDeleteResult.Deleted(
                    StoredSyncState(
                        version = tombstoneVersion,
                        record = null,
                        updatedAtEpochMs = updatedAtEpochMs,
                    ),
                )
            }
        }

    override fun changePasswordAndWrapper(
        userId: String,
        expectedCurrent: PasswordDigest,
        replacement: PasswordDigest,
        expectedSyncVersion: Long,
        replacementWrap: StoredKeyWrap,
        replacementSession: NewSession,
        updatedAtEpochMs: Long,
    ): PasswordChangeWriteResult =
        synchronized(lock) {
            require(replacementSession.userId == userId)
            try {
                transaction {
                    val currentSync = getSyncStateLocked(userId)
                    if (currentSync.version != expectedSyncVersion) {
                        return@transaction PasswordChangeWriteResult.VersionConflict(currentSync.version)
                    }
                    if (
                        currentSync.record != null &&
                        currentSync.record.keyVersion != replacementWrap.keyVersion
                    ) {
                        return@transaction PasswordChangeWriteResult.KeyVersionConflict(
                            currentSync.version,
                        )
                    }
                    val changed =
                        connection
                            .prepareStatement(
                                """
                                UPDATE users
                                SET password_salt = ?, password_hash = ?, password_iterations = ?,
                                    updated_at_ms = ?
                                WHERE id = ?
                                  AND password_salt = ?
                                  AND password_hash = ?
                                  AND password_iterations = ?
                                """.trimIndent(),
                            ).use { statement ->
                                statement.setBytes(1, replacement.salt)
                                statement.setBytes(2, replacement.hash)
                                statement.setInt(3, replacement.iterations)
                                statement.setLong(4, updatedAtEpochMs)
                                statement.setString(5, userId)
                                statement.setBytes(6, expectedCurrent.salt)
                                statement.setBytes(7, expectedCurrent.hash)
                                statement.setInt(8, expectedCurrent.iterations)
                                statement.executeUpdate()
                            }
                    if (changed != 1) return@transaction PasswordChangeWriteResult.CredentialsChanged

                    if (currentSync.record != null) {
                        val wrapperChanged =
                            connection
                                .prepareStatement(
                                    """
                                    UPDATE sync_records
                                    SET wrap_version = ?, wrap_kdf = ?, wrap_iterations = ?,
                                        wrapped_vault_key = ?, wrap_salt = ?, wrap_nonce = ?
                                    WHERE user_id = ? AND version = ?
                                    """.trimIndent(),
                                ).use { statement ->
                                    statement.setInt(1, replacementWrap.wrapVersion)
                                    statement.setString(2, replacementWrap.wrapKdf)
                                    statement.setInt(3, replacementWrap.wrapIterations)
                                    statement.setBytes(4, replacementWrap.wrappedVaultKey)
                                    statement.setBytes(5, replacementWrap.wrapSalt)
                                    statement.setBytes(6, replacementWrap.wrapNonce)
                                    statement.setString(7, userId)
                                    statement.setLong(8, expectedSyncVersion)
                                    statement.executeUpdate()
                                }
                        if (wrapperChanged != 1) throw SyncVersionChangedDuringPasswordUpdate()
                    }
                    connection
                        .prepareStatement(
                            "DELETE FROM sessions WHERE user_id = ?",
                        ).use { statement ->
                            statement.setString(1, userId)
                            statement.executeUpdate()
                        }
                    insertSessionLocked(replacementSession)
                    PasswordChangeWriteResult.Changed
                }
            } catch (_: SyncVersionChangedDuringPasswordUpdate) {
                PasswordChangeWriteResult.VersionConflict(getSyncStateLocked(userId).version)
            }
        }

    override fun close() {
        synchronized(lock) {
            if (!connection.isClosed) connection.close()
        }
    }

    private fun findUserByNormalizedUsernameLocked(normalizedUsername: String): StoredCredentials? =
        connection
            .prepareStatement(
                "SELECT $CREDENTIAL_COLUMNS FROM users u WHERE u.username_normalized = ? LIMIT 1",
            ).use { statement ->
                statement.setString(1, normalizedUsername)
                statement.executeQuery().use { result ->
                    if (result.next()) result.readCredentials() else null
                }
            }

    private fun findCredentialsByUserIdLocked(userId: String): StoredCredentials? =
        connection
            .prepareStatement(
                "SELECT $CREDENTIAL_COLUMNS FROM users u WHERE u.id = ? LIMIT 1",
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.readCredentials() else null
                }
            }

    private fun userCountLocked(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM users").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun findUserByIdLocked(userId: String): StoredUser? =
        connection
            .prepareStatement(
                "SELECT $USER_COLUMNS FROM users u WHERE u.id = ? LIMIT 1",
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result ->
                    if (result.next()) result.readUser() else null
                }
            }

    private fun isActiveSessionLocked(
        sessionId: String,
        userId: String,
        nowEpochMs: Long,
    ): Boolean =
        connection
            .prepareStatement(
                """
                SELECT 1 FROM sessions
                WHERE id = ?
                  AND user_id = ?
                  AND revoked_at_ms IS NULL
                  AND access_expires_at_ms > ?
                LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, sessionId)
                statement.setString(2, userId)
                statement.setLong(3, nowEpochMs)
                statement.executeQuery().use(ResultSet::next)
            }

    private fun insertSessionLocked(session: NewSession) {
        connection
            .prepareStatement(
                """
                INSERT INTO sessions (
                    id, user_id, access_token_hash, refresh_token_hash,
                    access_expires_at_ms, refresh_expires_at_ms, created_at_ms,
                    device_name, last_seen_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, session.id)
                statement.setString(2, session.userId)
                statement.setBytes(3, session.accessTokenHash)
                statement.setBytes(4, session.refreshTokenHash)
                statement.setLong(5, session.accessExpiresAtEpochMs)
                statement.setLong(6, session.refreshExpiresAtEpochMs)
                statement.setLong(7, session.createdAtEpochMs)
                statement.setString(8, session.deviceName)
                statement.setLong(9, session.createdAtEpochMs)
                statement.executeUpdate()
            }
    }

    private fun pruneSessionsLocked(nowEpochMs: Long) {
        connection
            .prepareStatement(
                """
                DELETE FROM sessions
                WHERE refresh_expires_at_ms <= ?
                   OR (revoked_at_ms IS NOT NULL AND revoked_at_ms <= ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, nowEpochMs)
                statement.setLong(2, nowEpochMs - REVOKED_SESSION_RETENTION_MS)
                statement.executeUpdate()
            }
    }

    private fun trimActiveSessionsForUserLocked(
        userId: String,
        nowEpochMs: Long,
    ) {
        connection
            .prepareStatement(
                """
                DELETE FROM sessions
                WHERE rowid IN (
                    SELECT rowid FROM sessions
                    WHERE user_id = ?
                      AND revoked_at_ms IS NULL
                      AND refresh_expires_at_ms > ?
                    ORDER BY created_at_ms DESC, rowid DESC
                    LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setLong(2, nowEpochMs)
                statement.setInt(3, activeSessionsPerUserLimit)
                statement.executeUpdate()
            }
    }

    private fun getSyncRecordLocked(userId: String): StoredSyncRecord? =
        connection
            .prepareStatement(
                """
                SELECT user_id, version, schema_version, algorithm, key_version, nonce,
                       ciphertext, wrap_version, wrap_kdf, wrap_iterations,
                       wrapped_vault_key, wrap_salt, wrap_nonce, updated_at_ms
                FROM sync_records WHERE user_id = ? LIMIT 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { result ->
                    if (!result.next()) {
                        null
                    } else {
                        StoredSyncRecord(
                            userId = result.getString("user_id"),
                            version = result.getLong("version"),
                            schemaVersion = result.getInt("schema_version"),
                            algorithm = result.getString("algorithm"),
                            keyVersion = result.getInt("key_version"),
                            nonce = result.getBytes("nonce"),
                            ciphertext = result.getBytes("ciphertext"),
                            wrapVersion = result.getNullableInt("wrap_version"),
                            wrapKdf = result.getString("wrap_kdf"),
                            wrapIterations = result.getNullableInt("wrap_iterations"),
                            wrappedVaultKey = result.getBytes("wrapped_vault_key"),
                            wrapSalt = result.getBytes("wrap_salt"),
                            wrapNonce = result.getBytes("wrap_nonce"),
                            updatedAtEpochMs = result.getLong("updated_at_ms"),
                        )
                    }
                }
            }

    private fun getSyncStateLocked(userId: String): StoredSyncState {
        val revision =
            connection
                .prepareStatement(
                    "SELECT version, updated_at_ms FROM sync_revisions WHERE user_id = ? LIMIT 1",
                ).use { statement ->
                    statement.setString(1, userId)
                    statement.executeQuery().use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            result.getLong("version") to
                                result.getLong("updated_at_ms")
                        }
                    }
                }
        val record = getSyncRecordLocked(userId)
        if (revision == null) {
            check(record == null) { "sync payload exists without a revision" }
            return StoredSyncState(version = 0L, record = null, updatedAtEpochMs = null)
        }
        check(record == null || record.version == revision.first) {
            "sync payload revision does not match the monotonic revision"
        }
        return StoredSyncState(
            version = revision.first,
            record = record,
            updatedAtEpochMs = revision.second,
        )
    }

    private fun upsertSyncRevisionLocked(
        userId: String,
        version: Long,
        updatedAtEpochMs: Long,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO sync_revisions(user_id, version, updated_at_ms)
                VALUES (?, ?, ?)
                ON CONFLICT(user_id) DO UPDATE SET
                    version = excluded.version,
                    updated_at_ms = excluded.updated_at_ms
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setLong(2, version)
                statement.setLong(3, updatedAtEpochMs)
                check(statement.executeUpdate() == 1) { "sync revision was not updated" }
            }
    }

    private fun insertSyncRecordLocked(record: StoredSyncRecord) {
        connection
            .prepareStatement(
                """
                INSERT INTO sync_records (
                    user_id, version, schema_version, algorithm, key_version, nonce,
                    ciphertext, wrap_version, wrap_kdf, wrap_iterations,
                    wrapped_vault_key, wrap_salt, wrap_nonce, updated_at_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.bindSyncRecord(record)
                statement.executeUpdate()
            }
    }

    private fun updateSyncRecordLocked(record: StoredSyncRecord) {
        connection
            .prepareStatement(
                """
                UPDATE sync_records
                SET version = ?, schema_version = ?, algorithm = ?, key_version = ?, nonce = ?,
                    ciphertext = ?, wrap_version = ?, wrap_kdf = ?, wrap_iterations = ?,
                    wrapped_vault_key = ?, wrap_salt = ?, wrap_nonce = ?, updated_at_ms = ?
                WHERE user_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, record.version)
                statement.setInt(2, record.schemaVersion)
                statement.setString(3, record.algorithm)
                statement.setInt(4, record.keyVersion)
                statement.setBytes(5, record.nonce)
                statement.setBytes(6, record.ciphertext)
                statement.setNullableInt(7, record.wrapVersion)
                statement.setString(8, record.wrapKdf)
                statement.setNullableInt(9, record.wrapIterations)
                statement.setBytes(10, record.wrappedVaultKey)
                statement.setBytes(11, record.wrapSalt)
                statement.setBytes(12, record.wrapNonce)
                statement.setLong(13, record.updatedAtEpochMs)
                statement.setString(14, record.userId)
                check(statement.executeUpdate() == 1) { "sync record disappeared during update" }
            }
    }

    private fun java.sql.PreparedStatement.bindSyncRecord(record: StoredSyncRecord) {
        setString(1, record.userId)
        setLong(2, record.version)
        setInt(3, record.schemaVersion)
        setString(4, record.algorithm)
        setInt(5, record.keyVersion)
        setBytes(6, record.nonce)
        setBytes(7, record.ciphertext)
        setNullableInt(8, record.wrapVersion)
        setString(9, record.wrapKdf)
        setNullableInt(10, record.wrapIterations)
        setBytes(11, record.wrappedVaultKey)
        setBytes(12, record.wrapSalt)
        setBytes(13, record.wrapNonce)
        setLong(14, record.updatedAtEpochMs)
    }

    private fun ensureColumnLocked(
        table: String,
        column: String,
        definition: String,
    ) {
        val exists =
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info($table)").use { result ->
                    var found = false
                    while (result.next()) {
                        if (result.getString("name") == column) {
                            found = true
                            break
                        }
                    }
                    found
                }
            }
        if (!exists) {
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE $table ADD COLUMN $column $definition")
            }
        }
    }

    /**
     * The first invite schema treated the optional redeemer id as the redemption marker. That
     * conflicts with `ON DELETE SET NULL`: deleting the invited account either violated the old
     * CHECK constraint or, without it, made a consumed code look unused. Rebuild that one schema
     * in-place so the immutable redemption timestamp is authoritative and the user id can be
     * anonymized without reviving the invitation.
     */
    private fun migrateInviteRedemptionAuthorityLocked() {
        val schema =
            connection
                .prepareStatement(
                    "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'account_invites'",
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next()) { "account_invites schema is missing" }
                        result.getString("sql")
                    }
                }
        val normalized = schema.lowercase().replace(Regex("\\s+"), " ")
        val currentConstraint =
            "check(redeemed_at_ms is not null or redeemed_by_user_id is null)"
        if (currentConstraint in normalized) return

        val legacyConstraint =
            "check((redeemed_by_user_id is null) = (redeemed_at_ms is null))"
        check(legacyConstraint in normalized) { "Unsupported account_invites schema" }

        transaction {
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE account_invites_v2 (
                        invite_hash BLOB PRIMARY KEY CHECK(length(invite_hash) = 32),
                        issuer_user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        created_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        redeemed_by_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
                        redeemed_at_ms INTEGER,
                        revoked_at_ms INTEGER,
                        CHECK(expires_at_ms > created_at_ms),
                        CHECK(redeemed_at_ms IS NOT NULL OR redeemed_by_user_id IS NULL)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    INSERT INTO account_invites_v2(
                        invite_hash, issuer_user_id, created_at_ms, expires_at_ms,
                        redeemed_by_user_id, redeemed_at_ms, revoked_at_ms
                    )
                    SELECT invite_hash, issuer_user_id, created_at_ms, expires_at_ms,
                           redeemed_by_user_id, redeemed_at_ms, revoked_at_ms
                    FROM account_invites
                    """.trimIndent(),
                )
                statement.execute("DROP TABLE account_invites")
                statement.execute("ALTER TABLE account_invites_v2 RENAME TO account_invites")
                statement.execute(
                    """
                    CREATE INDEX account_invites_issuer_idx
                    ON account_invites(issuer_user_id, created_at_ms)
                    """.trimIndent(),
                )
            }
        }
    }

    private fun cleanupNonceHistoryLocked(nowEpochMs: Long) {
        val clockMovedBackwards = nowEpochMs < lastNonceHistoryClockEpochMs
        lastNonceHistoryClockEpochMs = nowEpochMs
        if (clockMovedBackwards) {
            nextNonceHistoryCleanupAtEpochMs =
                saturatedAdd(
                    nowEpochMs,
                    nonceHistoryCleanupIntervalMs,
                )
            return
        }
        if (nowEpochMs < nextNonceHistoryCleanupAtEpochMs) return
        connection
            .prepareStatement(
                "DELETE FROM sync_nonce_history WHERE recorded_at_ms < ?",
            ).use { statement ->
                statement.setLong(1, saturatedSubtract(nowEpochMs, nonceHistoryRetentionMs))
                statement.executeUpdate()
            }
        nextNonceHistoryCleanupAtEpochMs =
            saturatedAdd(
                nowEpochMs,
                nonceHistoryCleanupIntervalMs,
            )
    }

    private fun trimNonceHistoryForUserLocked(userId: String) {
        connection
            .prepareStatement(
                """
                DELETE FROM sync_nonce_history
                WHERE rowid IN (
                    SELECT rowid FROM sync_nonce_history
                    WHERE user_id = ?
                    ORDER BY recorded_at_ms DESC, rowid DESC
                    LIMIT -1 OFFSET ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, userId)
                statement.setInt(2, nonceHistoryPerUserLimit)
                statement.executeUpdate()
            }
    }

    private fun saturatedAdd(
        left: Long,
        right: Long,
    ): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun saturatedSubtract(
        left: Long,
        right: Long,
    ): Long = if (left < Long.MIN_VALUE + right) Long.MIN_VALUE else left - right

    private inline fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        return try {
            block().also { connection.commit() }
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }
            throw failure
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    companion object {
        private const val REVOKED_SESSION_RETENTION_MS = 24 * 60 * 60_000L
        private const val USER_COLUMNS = """
            u.id AS user_id,
            u.username AS user_username,
            u.username_normalized AS user_username_normalized,
            u.nickname AS user_nickname,
            u.avatar_id AS user_avatar_id,
            u.created_at_ms AS user_created_at_ms,
            u.updated_at_ms AS user_updated_at_ms
        """
        private const val CREDENTIAL_COLUMNS = """
            $USER_COLUMNS,
            u.password_salt AS user_password_salt,
            u.password_hash AS user_password_hash,
            u.password_iterations AS user_password_iterations
        """

        fun open(
            databaseFile: File,
            nonceHistoryPerUserLimit: Int = DEFAULT_NONCE_HISTORY_PER_USER_LIMIT,
            nonceHistoryRetentionMs: Long = DEFAULT_NONCE_HISTORY_RETENTION_MS,
            nonceHistoryCleanupIntervalMs: Long = DEFAULT_NONCE_HISTORY_CLEANUP_INTERVAL_MS,
            activeSessionsPerUserLimit: Int = DEFAULT_ACTIVE_SESSIONS_PER_USER_LIMIT,
        ): SqliteAccountStore {
            val absoluteFile = databaseFile.absoluteFile
            val parent = absoluteFile.parentFile
            require(parent == null || parent.isDirectory || parent.mkdirs()) {
                "Unable to create account database directory"
            }
            return openUrl(
                url = "jdbc:sqlite:${absoluteFile.path}",
                fileBacked = true,
                nonceHistoryPerUserLimit = nonceHistoryPerUserLimit,
                nonceHistoryRetentionMs = nonceHistoryRetentionMs,
                nonceHistoryCleanupIntervalMs = nonceHistoryCleanupIntervalMs,
                activeSessionsPerUserLimit = activeSessionsPerUserLimit,
            )
        }

        fun inMemory(
            nonceHistoryPerUserLimit: Int = DEFAULT_NONCE_HISTORY_PER_USER_LIMIT,
            nonceHistoryRetentionMs: Long = DEFAULT_NONCE_HISTORY_RETENTION_MS,
            nonceHistoryCleanupIntervalMs: Long = DEFAULT_NONCE_HISTORY_CLEANUP_INTERVAL_MS,
            activeSessionsPerUserLimit: Int = DEFAULT_ACTIVE_SESSIONS_PER_USER_LIMIT,
        ): SqliteAccountStore =
            openUrl(
                url = "jdbc:sqlite::memory:",
                fileBacked = false,
                nonceHistoryPerUserLimit = nonceHistoryPerUserLimit,
                nonceHistoryRetentionMs = nonceHistoryRetentionMs,
                nonceHistoryCleanupIntervalMs = nonceHistoryCleanupIntervalMs,
                activeSessionsPerUserLimit = activeSessionsPerUserLimit,
            )

        private fun openUrl(
            url: String,
            fileBacked: Boolean,
            nonceHistoryPerUserLimit: Int,
            nonceHistoryRetentionMs: Long,
            nonceHistoryCleanupIntervalMs: Long,
            activeSessionsPerUserLimit: Int,
        ): SqliteAccountStore {
            Class.forName("org.sqlite.JDBC")
            return SqliteAccountStore(
                connection = DriverManager.getConnection(url),
                fileBacked = fileBacked,
                nonceHistoryPerUserLimit = nonceHistoryPerUserLimit,
                nonceHistoryRetentionMs = nonceHistoryRetentionMs,
                nonceHistoryCleanupIntervalMs = nonceHistoryCleanupIntervalMs,
                activeSessionsPerUserLimit = activeSessionsPerUserLimit,
            )
        }

        private const val DEFAULT_NONCE_HISTORY_PER_USER_LIMIT = 4_096
        private const val DEFAULT_NONCE_HISTORY_RETENTION_MS = 180L * 24 * 60 * 60_000L
        private const val DEFAULT_NONCE_HISTORY_CLEANUP_INTERVAL_MS = 60 * 60_000L
        private const val DEFAULT_ACTIVE_SESSIONS_PER_USER_LIMIT = 10
    }
}

private fun java.sql.PreparedStatement.setNullableInt(
    index: Int,
    value: Int?,
) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}

private class SyncVersionChangedDuringPasswordUpdate : RuntimeException()

private fun ResultSet.getNullableInt(column: String): Int? {
    val value = getInt(column)
    return if (wasNull()) null else value
}

private fun ResultSet.readUser(): StoredUser =
    StoredUser(
        id = getString("user_id"),
        username = getString("user_username"),
        normalizedUsername = getString("user_username_normalized"),
        nickname = getString("user_nickname"),
        avatarId = getInt("user_avatar_id"),
        createdAtEpochMs = getLong("user_created_at_ms"),
        updatedAtEpochMs = getLong("user_updated_at_ms"),
    )

private fun ResultSet.readCredentials(): StoredCredentials =
    StoredCredentials(
        user = readUser(),
        passwordSalt = getBytes("user_password_salt"),
        passwordHash = getBytes("user_password_hash"),
        passwordIterations = getInt("user_password_iterations"),
    )
