package com.yfuse.watch.account

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
internal data class EncryptedPlaybackEntity(
    val entityKey: String,
    val mutationId: String,
    val schemaVersion: Int = 1,
    val algorithm: String = "AES-256-GCM",
    val keyVersion: Int = 1,
    val nonce: String,
    val ciphertext: String,
    val cursor: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
)

@Serializable
internal data class PlaybackPutItem(
    val baseCursor: Long,
    val entity: EncryptedPlaybackEntity,
)

@Serializable
internal data class PlaybackPushRequest(
    val items: List<PlaybackPutItem>,
)

@Serializable
internal data class PlaybackAcceptedEntity(
    val entityKey: String,
    val mutationId: String,
    val cursor: Long,
)

@Serializable
internal data class PlaybackPushResponse(
    val cursor: Long,
    val accepted: List<PlaybackAcceptedEntity> = emptyList(),
    val conflicts: List<EncryptedPlaybackEntity> = emptyList(),
)

@Serializable
internal data class PlaybackDeltaResponse(
    val cursor: Long,
    val changes: List<EncryptedPlaybackEntity> = emptyList(),
    val hasMore: Boolean = false,
)

/**
 * Opaque, cursor-addressed current-state relay. The media key and document are both client-side
 * secrets: [entityKey] is HMAC-SHA256 under the account vault key and [ciphertext] is AES-GCM.
 */
internal class PlaybackRelayStore private constructor(
    private val connection: Connection,
) : AutoCloseable {
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playback_sync_cursors (
                        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
                        cursor INTEGER NOT NULL CHECK(cursor >= 0)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS playback_sync_entities (
                        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                        entity_key TEXT NOT NULL,
                        cursor INTEGER NOT NULL CHECK(cursor > 0),
                        mutation_id TEXT NOT NULL,
                        schema_version INTEGER NOT NULL,
                        algorithm TEXT NOT NULL,
                        key_version INTEGER NOT NULL,
                        nonce BLOB NOT NULL CHECK(length(nonce) = 12),
                        ciphertext BLOB NOT NULL,
                        updated_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(user_id, entity_key),
                        UNIQUE(user_id, mutation_id)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS playback_sync_entities_cursor_idx
                    ON playback_sync_entities(user_id, cursor)
                    """.trimIndent(),
                )
            }
        }
    }

    fun pull(
        userId: String,
        afterCursor: Long,
        limit: Int,
    ): PlaybackDeltaResponse = synchronized(lock) {
        val globalCursor = cursorLocked(userId)
        val rows = mutableListOf<EncryptedPlaybackEntity>()
        var encodedBudget = 0
        connection.prepareStatement(
            """
            SELECT entity_key, cursor, mutation_id, schema_version, algorithm, key_version,
                   nonce, ciphertext, updated_at_ms
            FROM playback_sync_entities
            WHERE user_id = ? AND cursor > ?
            ORDER BY cursor ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, userId)
            statement.setLong(2, afterCursor.coerceAtLeast(0L))
            statement.setInt(3, limit.coerceIn(1, MAX_PULL_ITEMS) + 1)
            statement.executeQuery().use { result ->
                while (result.next() && rows.size < limit) {
                    val ciphertext = result.getBytes("ciphertext")
                    val projected = encodedBudget + base64UrlEncodedLength(ciphertext.size)
                    if (rows.isNotEmpty() && projected > MAX_PULL_CIPHERTEXT_BUDGET) break
                    encodedBudget = projected
                    rows +=
                        EncryptedPlaybackEntity(
                            entityKey = result.getString("entity_key"),
                            mutationId = result.getString("mutation_id"),
                            schemaVersion = result.getInt("schema_version"),
                            algorithm = result.getString("algorithm"),
                            keyVersion = result.getInt("key_version"),
                            nonce = result.getBytes("nonce").toBase64Url(),
                            ciphertext = ciphertext.toBase64Url(),
                            cursor = result.getLong("cursor"),
                            updatedAtEpochMs = result.getLong("updated_at_ms"),
                        )
                }
            }
        }
        val pageCursor = rows.lastOrNull()?.cursor ?: globalCursor
        val hasMore =
            if (rows.isEmpty()) false
            else hasEntityAfterLocked(userId, pageCursor)
        PlaybackDeltaResponse(
            cursor = if (hasMore) pageCursor else globalCursor,
            changes = rows,
            hasMore = hasMore,
        )
    }

    fun push(
        userId: String,
        request: PlaybackPushRequest,
        nowEpochMs: Long,
    ): PlaybackPushResponse = synchronized(lock) {
        require(request.items.size in 1..MAX_PUSH_ITEMS) { "playback batch size invalid" }
        val normalized = request.items.map(::validatePutItem)
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            var cursor = cursorLocked(userId)
            val accepted = mutableListOf<PlaybackAcceptedEntity>()
            val conflicts = mutableListOf<EncryptedPlaybackEntity>()
            normalized.forEach { item ->
                val current = entityLocked(userId, item.entity.entityKey)
                if (current?.mutationId == item.entity.mutationId) {
                    accepted +=
                        PlaybackAcceptedEntity(
                            entityKey = current.entityKey,
                            mutationId = current.mutationId,
                            cursor = current.cursor,
                        )
                    return@forEach
                }
                val currentCursor = current?.cursor ?: 0L
                if (currentCursor != item.baseCursor) {
                    current?.let(conflicts::add)
                    return@forEach
                }
                cursor++
                upsertLocked(
                    userId = userId,
                    entity = item.entity,
                    cursor = cursor,
                    nowEpochMs = nowEpochMs,
                )
                accepted +=
                    PlaybackAcceptedEntity(
                        entityKey = item.entity.entityKey,
                        mutationId = item.entity.mutationId,
                        cursor = cursor,
                    )
            }
            setCursorLocked(userId, cursor)
            connection.commit()
            PlaybackPushResponse(cursor = cursor, accepted = accepted, conflicts = conflicts)
        } catch (error: Throwable) {
            runCatching { connection.rollback() }
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun validatePutItem(item: PlaybackPutItem): PlaybackPutItem {
        require(item.baseCursor >= 0L) { "playback base cursor invalid" }
        val entity = item.entity
        require(ENTITY_KEY_PATTERN.matches(entity.entityKey)) { "playback entity key invalid" }
        require(entity.mutationId.length in 8..128 && entity.mutationId.all { !it.isWhitespace() }) {
            "playback mutation id invalid"
        }
        require(entity.schemaVersion == 1 && entity.algorithm == "AES-256-GCM" && entity.keyVersion == 1) {
            "playback crypto metadata invalid"
        }
        val nonce = entity.nonce.decodeBase64Url()
        val ciphertext = entity.ciphertext.decodeBase64Url()
        require(nonce.size == 12) { "playback nonce invalid" }
        require(ciphertext.size in 16..MAX_PLAYBACK_CIPHERTEXT_BYTES) { "playback ciphertext invalid" }
        return item.copy(entity = entity.copy(cursor = 0L, updatedAtEpochMs = 0L))
    }

    private fun entityLocked(
        userId: String,
        entityKey: String,
    ): EncryptedPlaybackEntity? =
        connection.prepareStatement(
            """
            SELECT entity_key, cursor, mutation_id, schema_version, algorithm, key_version,
                   nonce, ciphertext, updated_at_ms
            FROM playback_sync_entities
            WHERE user_id = ? AND entity_key = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, entityKey)
            statement.executeQuery().use { result ->
                if (!result.next()) return@use null
                EncryptedPlaybackEntity(
                    entityKey = result.getString("entity_key"),
                    mutationId = result.getString("mutation_id"),
                    schemaVersion = result.getInt("schema_version"),
                    algorithm = result.getString("algorithm"),
                    keyVersion = result.getInt("key_version"),
                    nonce = result.getBytes("nonce").toBase64Url(),
                    ciphertext = result.getBytes("ciphertext").toBase64Url(),
                    cursor = result.getLong("cursor"),
                    updatedAtEpochMs = result.getLong("updated_at_ms"),
                )
            }
        }

    private fun upsertLocked(
        userId: String,
        entity: EncryptedPlaybackEntity,
        cursor: Long,
        nowEpochMs: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO playback_sync_entities(
                user_id, entity_key, cursor, mutation_id, schema_version, algorithm, key_version,
                nonce, ciphertext, updated_at_ms
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(user_id, entity_key) DO UPDATE SET
                cursor = excluded.cursor,
                mutation_id = excluded.mutation_id,
                schema_version = excluded.schema_version,
                algorithm = excluded.algorithm,
                key_version = excluded.key_version,
                nonce = excluded.nonce,
                ciphertext = excluded.ciphertext,
                updated_at_ms = excluded.updated_at_ms
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, entity.entityKey)
            statement.setLong(3, cursor)
            statement.setString(4, entity.mutationId)
            statement.setInt(5, entity.schemaVersion)
            statement.setString(6, entity.algorithm)
            statement.setInt(7, entity.keyVersion)
            statement.setBytes(8, entity.nonce.decodeBase64Url())
            statement.setBytes(9, entity.ciphertext.decodeBase64Url())
            statement.setLong(10, nowEpochMs)
            statement.executeUpdate()
        }
    }

    private fun cursorLocked(userId: String): Long =
        connection.prepareStatement(
            "SELECT cursor FROM playback_sync_cursors WHERE user_id = ?",
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
        }

    private fun setCursorLocked(
        userId: String,
        cursor: Long,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO playback_sync_cursors(user_id, cursor) VALUES(?, ?)
            ON CONFLICT(user_id) DO UPDATE SET cursor = excluded.cursor
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, userId)
            statement.setLong(2, cursor)
            statement.executeUpdate()
        }
    }

    private fun hasEntityAfterLocked(
        userId: String,
        cursor: Long,
    ): Boolean =
        connection.prepareStatement(
            "SELECT 1 FROM playback_sync_entities WHERE user_id = ? AND cursor > ? LIMIT 1",
        ).use { statement ->
            statement.setString(1, userId)
            statement.setLong(2, cursor)
            statement.executeQuery().use { it.next() }
        }

    override fun close() {
        synchronized(lock) { connection.close() }
    }

    companion object {
        fun fromEnvironment(): PlaybackRelayStore {
            val file = File(System.getenv("ACCOUNT_DB_PATH") ?: "/var/lib/yfuse/account.db")
            file.parentFile?.mkdirs()
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
            return PlaybackRelayStore(connection)
        }

        internal fun inMemoryForTests(): PlaybackRelayStore {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            val store = PlaybackRelayStore(connection)
            // Production always has the account `users` table and intentionally keeps FK checks
            // enabled. This isolated unit store has no account schema; disable only after the
            // relay constructor has created its own tables so tests exercise relay semantics.
            connection.createStatement().use {
                it.execute("PRAGMA foreign_keys = OFF")
            }
            return store
        }
    }
}

internal object PlaybackRelayStoreProvider {
    val instance: PlaybackRelayStore by lazy(PlaybackRelayStore::fromEnvironment)
}

private fun ByteArray.toBase64Url(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(this)

private fun String.decodeBase64Url(): ByteArray =
    runCatching { Base64.getUrlDecoder().decode(this) }
        .getOrElse { throw IllegalArgumentException("invalid base64url") }

private val ENTITY_KEY_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private const val MAX_PLAYBACK_CIPHERTEXT_BYTES = 96 * 1024 + 16
private const val MAX_PULL_CIPHERTEXT_BUDGET = 192 * 1024
private const val MAX_PULL_ITEMS = 200
private const val MAX_PUSH_ITEMS = 64
