package com.yfuse.watch

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

internal sealed interface CalendarOcrCacheLookup {
    data class Success(val text: String) : CalendarOcrCacheLookup
    data object RecentFailure : CalendarOcrCacheLookup
    data object Miss : CalendarOcrCacheLookup
}

internal interface CalendarOcrCache : AutoCloseable {
    fun lookup(providerKey: String, imageHash: String): CalendarOcrCacheLookup
    fun putSuccess(providerKey: String, imageHash: String, text: String)
    fun putFailure(providerKey: String, imageHash: String)
    override fun close() = Unit

    companion object {
        fun sqlite(file: File): CalendarOcrCache = SqliteCalendarOcrCache.open(file)
        fun inMemory(): CalendarOcrCache = SqliteCalendarOcrCache.openMemory()
    }
}

internal object NoOpCalendarOcrCache : CalendarOcrCache {
    override fun lookup(providerKey: String, imageHash: String) = CalendarOcrCacheLookup.Miss
    override fun putSuccess(providerKey: String, imageHash: String, text: String) = Unit
    override fun putFailure(providerKey: String, imageHash: String) = Unit
}

private class SqliteCalendarOcrCache private constructor(
    private val connection: Connection,
    private val fileBacked: Boolean,
) : CalendarOcrCache {
    private val lock = Any()

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout = 5000")
                if (fileBacked) {
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute("PRAGMA synchronous = NORMAL")
                }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_ocr_cache (
                        provider_key TEXT NOT NULL,
                        image_hash TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('success', 'failure')),
                        result_text TEXT,
                        updated_at_ms INTEGER NOT NULL,
                        expires_at_ms INTEGER NOT NULL,
                        PRIMARY KEY(provider_key, image_hash)
                    )
                    """.trimIndent(),
                )
                statement.execute("CREATE INDEX IF NOT EXISTS calendar_ocr_cache_expiry ON calendar_ocr_cache(expires_at_ms)")
            }
        }
    }

    override fun lookup(providerKey: String, imageHash: String): CalendarOcrCacheLookup = synchronized(lock) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            "SELECT status, result_text, expires_at_ms FROM calendar_ocr_cache WHERE provider_key = ? AND image_hash = ?",
        ).use { statement ->
            statement.setString(1, providerKey)
            statement.setString(2, imageHash)
            statement.executeQuery().use { result ->
                if (!result.next()) return@synchronized CalendarOcrCacheLookup.Miss
                if (result.getLong("expires_at_ms") <= now) {
                    delete(providerKey, imageHash)
                    return@synchronized CalendarOcrCacheLookup.Miss
                }
                when (result.getString("status")) {
                    "success" -> result.getString("result_text")?.let(CalendarOcrCacheLookup::Success)
                        ?: CalendarOcrCacheLookup.Miss
                    "failure" -> CalendarOcrCacheLookup.RecentFailure
                    else -> CalendarOcrCacheLookup.Miss
                }
            }
        }
    }

    override fun putSuccess(providerKey: String, imageHash: String, text: String) {
        if (text.isNotBlank() && text.length <= MAX_CACHED_OCR_CHARS) put(providerKey, imageHash, "success", text, SUCCESS_TTL_MS)
    }

    override fun putFailure(providerKey: String, imageHash: String) {
        put(providerKey, imageHash, "failure", null, FAILURE_TTL_MS)
    }

    private fun put(providerKey: String, imageHash: String, status: String, text: String?, ttlMillis: Long) = synchronized(lock) {
        val now = System.currentTimeMillis()
        connection.prepareStatement(
            """
            INSERT INTO calendar_ocr_cache(provider_key, image_hash, status, result_text, updated_at_ms, expires_at_ms)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(provider_key, image_hash) DO UPDATE SET status=excluded.status,
                result_text=excluded.result_text, updated_at_ms=excluded.updated_at_ms, expires_at_ms=excluded.expires_at_ms
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, providerKey)
            statement.setString(2, imageHash)
            statement.setString(3, status)
            statement.setString(4, text)
            statement.setLong(5, now)
            statement.setLong(6, now + ttlMillis)
            statement.executeUpdate()
        }
    }

    private fun delete(providerKey: String, imageHash: String) {
        connection.prepareStatement("DELETE FROM calendar_ocr_cache WHERE provider_key = ? AND image_hash = ?").use { statement ->
            statement.setString(1, providerKey)
            statement.setString(2, imageHash)
            statement.executeUpdate()
        }
    }

    override fun close() = synchronized(lock) { connection.close() }

    companion object {
        fun open(file: File): CalendarOcrCache {
            file.absoluteFile.parentFile?.mkdirs()
            return SqliteCalendarOcrCache(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"), true)
        }

        fun openMemory(): CalendarOcrCache = SqliteCalendarOcrCache(DriverManager.getConnection("jdbc:sqlite::memory:"), false)
    }
}

private const val SUCCESS_TTL_MS = 30L * 24 * 60 * 60 * 1_000
private const val FAILURE_TTL_MS = 15L * 60 * 1_000
private const val MAX_CACHED_OCR_CHARS = 2_000_000
