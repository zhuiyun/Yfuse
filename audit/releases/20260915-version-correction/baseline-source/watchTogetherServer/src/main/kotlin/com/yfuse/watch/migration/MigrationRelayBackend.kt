package com.yfuse.watch.migration

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-time key relay for six-digit server migration codes.
 *
 * The credential-bearing backup never reaches this service. The database holds only a digest of
 * the high-entropy relay id, the caller supplied ciphertext digest, an HMAC of the short code, and
 * the random transfer key wrapped by a deployment master key. A captured six-digit code is useless
 * without the migration envelope, which contains the unguessable relay id.
 */
internal class MigrationRelayBackend private constructor(
    private val store: MigrationRelayStore,
    masterKey: ByteArray,
    private val nowEpochMs: () -> Long,
    private val codeGenerator: () -> String,
    private val rateLimiter: MigrationRelayRateLimiter,
) : AutoCloseable {
    private val keyProtector = MigrationRelayKeyProtector(masterKey)

    init {
        require(masterKey.size == KEY_BYTES) { "Migration relay master key must be 32 bytes" }
        masterKey.fill(0)
    }

    fun create(
        request: CreateMigrationRelayRequest,
        clientIp: String,
    ): CreateMigrationRelayResponse {
        val now = nowEpochMs()
        rateLimiter.requireAllowed("create", clientIp, CREATE_LIMIT, CREATE_WINDOW_MS, now)
        val relayId = decodeFixed(request.relayId, RELAY_ID_BYTES, "relayId")
        val transferSecret = decodeFixed(request.transferSecret, KEY_BYTES, "transferSecret")
        val payloadHash = decodeFixed(request.payloadSha256, HASH_BYTES, "payloadSha256")
        val relayHash = sha256(relayId)
        val expiresAt = Math.addExact(now, RELAY_TTL_MS)
        val code = codeGenerator().also(::requireSixDigitCode)
        val codeHmac = keyProtector.codeHmac(relayHash, code)
        val wrapped = keyProtector.wrap(transferSecret, relayHash, payloadHash, expiresAt)
        return try {
            val inserted =
                store.insert(
                    StoredMigrationRelay(
                        relayHash = relayHash,
                        codeHmac = codeHmac,
                        payloadHash = payloadHash,
                        wrappedSecretNonce = wrapped.nonce,
                        wrappedSecret = wrapped.ciphertext,
                        createdAtEpochMs = now,
                        expiresAtEpochMs = expiresAt,
                    ),
                    now,
                )
            if (!inserted) invalidCreate()
            CreateMigrationRelayResponse(code, expiresAt)
        } finally {
            relayId.fill(0)
            transferSecret.fill(0)
            relayHash.fill(0)
            payloadHash.fill(0)
            codeHmac.fill(0)
            wrapped.nonce.fill(0)
            wrapped.ciphertext.fill(0)
        }
    }

    fun redeem(
        request: RedeemMigrationRelayRequest,
        clientIp: String,
    ): RedeemMigrationRelayResponse {
        val now = nowEpochMs()
        rateLimiter.requireAllowed("redeem", clientIp, REDEEM_LIMIT, REDEEM_WINDOW_MS, now)
        requireSixDigitCode(request.code)
        val relayId = decodeFixed(request.relayId, RELAY_ID_BYTES, "relayId")
        val payloadHash = decodeFixed(request.payloadSha256, HASH_BYTES, "payloadSha256")
        val relayHash = sha256(relayId)
        val codeHmac = keyProtector.codeHmac(relayHash, request.code)
        return try {
            val secret =
                store.redeem(relayHash, codeHmac, payloadHash, now) { record ->
                    keyProtector.unwrap(record)
                } ?: invalidRedeem()
            try {
                RedeemMigrationRelayResponse(ENCODER.encodeToString(secret))
            } finally {
                secret.fill(0)
            }
        } finally {
            relayId.fill(0)
            relayHash.fill(0)
            payloadHash.fill(0)
            codeHmac.fill(0)
        }
    }

    /** Side-effect-free readiness probe using the same SQLite connection as relay traffic. */
    internal fun healthCheck(): Boolean = store.healthCheck()

    override fun close() {
        keyProtector.close()
        store.close()
    }

    companion object {
        private const val KEY_BYTES = 32
        private const val HASH_BYTES = 32
        private const val RELAY_ID_BYTES = 32
        internal const val RELAY_TTL_MS = 15 * 60_000L
        internal const val MAX_REDEEM_ATTEMPTS = 5
        private const val CREATE_LIMIT = 20
        private const val CREATE_WINDOW_MS = 60 * 60_000L
        private const val REDEEM_LIMIT = 60
        private const val REDEEM_WINDOW_MS = 60 * 60_000L
        private const val MAX_ACTIVE_RELAYS = 10_000
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        fun sqlite(
            file: File,
            masterKey: ByteArray,
            nowEpochMs: () -> Long = System::currentTimeMillis,
            random: SecureRandom = SecureRandom(),
        ): MigrationRelayBackend =
            MigrationRelayBackend(
                store = SqliteMigrationRelayStore.open(file, MAX_ACTIVE_RELAYS),
                masterKey = masterKey.copyOf(),
                nowEpochMs = nowEpochMs,
                codeGenerator = { "%06d".format(random.nextInt(1_000_000)) },
                rateLimiter = MigrationRelayRateLimiter(),
            )

        fun inMemory(
            masterKey: ByteArray = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes),
            nowEpochMs: () -> Long = System::currentTimeMillis,
            codeGenerator: () -> String = {
                "%06d".format(SecureRandom().nextInt(1_000_000))
            },
            rateLimiter: MigrationRelayRateLimiter = MigrationRelayRateLimiter(),
        ): MigrationRelayBackend =
            MigrationRelayBackend(
                store = SqliteMigrationRelayStore.openMemory(MAX_ACTIVE_RELAYS),
                masterKey = masterKey.copyOf(),
                nowEpochMs = nowEpochMs,
                codeGenerator = codeGenerator,
                rateLimiter = rateLimiter,
            )

        fun fromEnvironment(): MigrationRelayBackend {
            val encodedKey = System.getenv("MIGRATION_RELAY_MASTER_KEY")?.trim().orEmpty()
            require(encodedKey.isNotEmpty()) { "MIGRATION_RELAY_MASTER_KEY is required" }
            val key =
                runCatching { DECODER.decode(encodedKey) }
                    .getOrElse { throw IllegalArgumentException("MIGRATION_RELAY_MASTER_KEY must be base64url", it) }
            require(ENCODER.encodeToString(key) == encodedKey && key.size == KEY_BYTES) {
                "MIGRATION_RELAY_MASTER_KEY must be a canonical 32-byte base64url value"
            }
            return try {
                sqlite(
                    file =
                        File(
                            System.getenv("MIGRATION_RELAY_DB_PATH")
                                ?: "/var/lib/yfuse/migration-relay.db",
                        ),
                    masterKey = key,
                )
            } finally {
                key.fill(0)
            }
        }

        internal fun encode(bytes: ByteArray): String = ENCODER.encodeToString(bytes)

        private fun decodeFixed(
            raw: String,
            expectedBytes: Int,
            field: String,
        ): ByteArray {
            if (raw.length > 128 || raw.any { it.isWhitespace() }) invalidInput()
            val decoded = runCatching { DECODER.decode(raw) }.getOrElse { invalidInput() }
            if (decoded.size != expectedBytes || ENCODER.encodeToString(decoded) != raw) {
                decoded.fill(0)
                invalidInput()
            }
            return decoded
        }

        private fun requireSixDigitCode(code: String) {
            if (code.length != 6 || code.any { it !in '0'..'9' }) invalidRedeem()
        }

        private fun invalidInput(): Nothing = throw MigrationRelayException("invalid_request", "迁移请求无效")

        private fun invalidCreate(): Nothing = throw MigrationRelayException("invalid_request", "迁移请求无效")

        private fun invalidRedeem(): Nothing = throw MigrationRelayException("invalid_migration_code", "迁移码无效或已失效")

        private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    }
}

internal data class StoredMigrationRelay(
    val relayHash: ByteArray,
    val codeHmac: ByteArray,
    val payloadHash: ByteArray,
    val wrappedSecretNonce: ByteArray,
    val wrappedSecret: ByteArray,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val attempts: Int = 0,
    val consumedAtEpochMs: Long? = null,
)

private data class WrappedSecret(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

private class MigrationRelayKeyProtector(
    masterKey: ByteArray,
) : AutoCloseable {
    private val wrappingKey = masterKey.copyOf()
    private val hmacKey = hmac(wrappingKey, "yfuse-migration-code-hmac-v1".toByteArray())
    private val random = SecureRandom()

    fun codeHmac(
        relayHash: ByteArray,
        code: String,
    ): ByteArray = hmac(hmacKey, relayHash + code.toByteArray(Charsets.US_ASCII))

    fun wrap(
        secret: ByteArray,
        relayHash: ByteArray,
        payloadHash: ByteArray,
        expiresAtEpochMs: Long,
    ): WrappedSecret {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(128, nonce),
        )
        cipher.updateAAD(aad(relayHash, payloadHash, expiresAtEpochMs))
        return WrappedSecret(nonce, cipher.doFinal(secret))
    }

    fun unwrap(record: StoredMigrationRelay): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(wrappingKey, "AES"),
            GCMParameterSpec(128, record.wrappedSecretNonce),
        )
        cipher.updateAAD(aad(record.relayHash, record.payloadHash, record.expiresAtEpochMs))
        return cipher.doFinal(record.wrappedSecret)
    }

    override fun close() {
        wrappingKey.fill(0)
        hmacKey.fill(0)
    }

    private fun aad(
        relayHash: ByteArray,
        payloadHash: ByteArray,
        expiresAtEpochMs: Long,
    ): ByteArray = relayHash + payloadHash + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(expiresAtEpochMs).array()

    companion object {
        private fun hmac(
            key: ByteArray,
            value: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(value)
            }
    }
}

internal class MigrationRelayRateLimiter(
    private val maxTrackedKeys: Int = 10_000,
) {
    private data class Window(
        var startedAtMs: Long,
        var count: Int,
    )

    private val windows = LinkedHashMap<String, Window>()

    @Synchronized
    fun requireAllowed(
        bucket: String,
        rawClientIp: String,
        limit: Int,
        windowMs: Long,
        nowEpochMs: Long,
    ) {
        val ip = rawClientIp.trim().take(128).ifBlank { "unknown" }
        val key = "$bucket:$ip"
        val current = windows[key]
        if (current == null || nowEpochMs - current.startedAtMs >= windowMs || nowEpochMs < current.startedAtMs) {
            if (windows.size >= maxTrackedKeys) windows.remove(windows.keys.first())
            windows[key] = Window(nowEpochMs, 1)
            return
        }
        if (current.count >= limit) {
            throw MigrationRelayException("rate_limited", "请求过于频繁，请稍后重试", rateLimited = true)
        }
        current.count++
    }
}

private interface MigrationRelayStore : AutoCloseable {
    fun healthCheck(): Boolean

    fun insert(
        record: StoredMigrationRelay,
        nowEpochMs: Long,
    ): Boolean

    fun redeem(
        relayHash: ByteArray,
        codeHmac: ByteArray,
        payloadHash: ByteArray,
        nowEpochMs: Long,
        unwrap: (StoredMigrationRelay) -> ByteArray,
    ): ByteArray?
}

private class SqliteMigrationRelayStore private constructor(
    private val connection: Connection,
    private val fileBacked: Boolean,
    private val maxActiveRelays: Int,
) : MigrationRelayStore {
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                if (fileBacked) {
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute("PRAGMA synchronous = FULL")
                }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS migration_relays (
                        relay_hash BLOB PRIMARY KEY CHECK(length(relay_hash) = 32),
                        code_hmac BLOB NOT NULL CHECK(length(code_hmac) = 32),
                        payload_hash BLOB NOT NULL CHECK(length(payload_hash) = 32),
                        wrapped_secret_nonce BLOB NOT NULL CHECK(length(wrapped_secret_nonce) = 12),
                        wrapped_secret BLOB NOT NULL CHECK(length(wrapped_secret) = 48),
                        created_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        attempts INTEGER NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 5),
                        consumed_at_ms INTEGER,
                        CHECK(expires_at_ms > created_at_ms)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_migration_relays_expiry ON migration_relays(expires_at_ms)",
                )
            }
        }
    }

    override fun healthCheck(): Boolean =
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT 1").use { result ->
                    result.next() && result.getInt(1) == 1
                }
            }
        }

    override fun insert(
        record: StoredMigrationRelay,
        nowEpochMs: Long,
    ): Boolean =
        synchronized(lock) {
            cleanup(nowEpochMs)
            val active =
                connection
                    .prepareStatement(
                        "SELECT COUNT(*) FROM migration_relays WHERE consumed_at_ms IS NULL AND expires_at_ms >= ?",
                    ).use { statement ->
                        statement.setLong(1, nowEpochMs)
                        statement.executeQuery().use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
            if (active >= maxActiveRelays) {
                throw MigrationRelayException("relay_capacity", "迁移服务繁忙，请稍后重试", rateLimited = true)
            }
            connection
                .prepareStatement(
                    """
                    INSERT OR IGNORE INTO migration_relays (
                        relay_hash, code_hmac, payload_hash, wrapped_secret_nonce, wrapped_secret,
                        created_at_ms, expires_at_ms, attempts, consumed_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, NULL)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setBytes(1, record.relayHash)
                    statement.setBytes(2, record.codeHmac)
                    statement.setBytes(3, record.payloadHash)
                    statement.setBytes(4, record.wrappedSecretNonce)
                    statement.setBytes(5, record.wrappedSecret)
                    statement.setLong(6, record.createdAtEpochMs)
                    statement.setLong(7, record.expiresAtEpochMs)
                    statement.executeUpdate() == 1
                }
        }

    override fun redeem(
        relayHash: ByteArray,
        codeHmac: ByteArray,
        payloadHash: ByteArray,
        nowEpochMs: Long,
        unwrap: (StoredMigrationRelay) -> ByteArray,
    ): ByteArray? =
        synchronized(lock) {
            connection.autoCommit = false
            try {
                val record =
                    connection
                        .prepareStatement(
                            """
                            SELECT relay_hash, code_hmac, payload_hash, wrapped_secret_nonce, wrapped_secret,
                                   created_at_ms, expires_at_ms, attempts, consumed_at_ms
                            FROM migration_relays WHERE relay_hash = ?
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setBytes(1, relayHash)
                            statement.executeQuery().use { result -> if (result.next()) result.toRecord() else null }
                        } ?: return@synchronized null.also { connection.commit() }

                val active =
                    record.consumedAtEpochMs == null &&
                        nowEpochMs <= record.expiresAtEpochMs &&
                        record.attempts < MigrationRelayBackend.MAX_REDEEM_ATTEMPTS
                val matches =
                    active &&
                        MessageDigest.isEqual(record.codeHmac, codeHmac) &&
                        MessageDigest.isEqual(record.payloadHash, payloadHash)
                if (!matches) {
                    if (active) {
                        connection
                            .prepareStatement(
                                "UPDATE migration_relays SET attempts = attempts + 1 WHERE relay_hash = ? AND attempts < 5",
                            ).use { statement ->
                                statement.setBytes(1, relayHash)
                                statement.executeUpdate()
                            }
                    }
                    connection.commit()
                    return@synchronized null
                }

                val secret = unwrap(record)
                val consumed =
                    connection
                        .prepareStatement(
                            """
                            UPDATE migration_relays SET consumed_at_ms = ?
                            WHERE relay_hash = ? AND consumed_at_ms IS NULL AND expires_at_ms >= ? AND attempts < 5
                            """.trimIndent(),
                        ).use { statement ->
                            statement.setLong(1, nowEpochMs)
                            statement.setBytes(2, relayHash)
                            statement.setLong(3, nowEpochMs)
                            statement.executeUpdate() == 1
                        }
                connection.commit()
                if (consumed) secret else null.also { secret.fill(0) }
            } catch (error: Exception) {
                runCatching { connection.rollback() }
                throw error
            } finally {
                connection.autoCommit = true
            }
        }

    private fun cleanup(nowEpochMs: Long) {
        connection
            .prepareStatement(
                "DELETE FROM migration_relays WHERE expires_at_ms < ? OR consumed_at_ms IS NOT NULL",
            ).use { statement ->
                statement.setLong(1, nowEpochMs)
                statement.executeUpdate()
            }
    }

    override fun close() = synchronized(lock) { connection.close() }

    companion object {
        fun open(
            file: File,
            maxActiveRelays: Int,
        ): SqliteMigrationRelayStore {
            val parent = requireNotNull(file.absoluteFile.parentFile) { "Migration relay DB needs a parent directory" }
            require((parent.isDirectory || parent.mkdirs()) && parent.canWrite()) {
                "Migration relay DB parent directory is not writable: $parent"
            }
            val store =
                SqliteMigrationRelayStore(
                    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"),
                    fileBacked = true,
                    maxActiveRelays = maxActiveRelays,
                )
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            return store
        }

        fun openMemory(maxActiveRelays: Int): SqliteMigrationRelayStore =
            SqliteMigrationRelayStore(
                DriverManager.getConnection("jdbc:sqlite::memory:"),
                fileBacked = false,
                maxActiveRelays = maxActiveRelays,
            )
    }
}

private fun ResultSet.toRecord(): StoredMigrationRelay =
    StoredMigrationRelay(
        relayHash = getBytes("relay_hash"),
        codeHmac = getBytes("code_hmac"),
        payloadHash = getBytes("payload_hash"),
        wrappedSecretNonce = getBytes("wrapped_secret_nonce"),
        wrappedSecret = getBytes("wrapped_secret"),
        createdAtEpochMs = getLong("created_at_ms"),
        expiresAtEpochMs = getLong("expires_at_ms"),
        attempts = getInt("attempts"),
        consumedAtEpochMs = getLong("consumed_at_ms").takeUnless { wasNull() },
    )
