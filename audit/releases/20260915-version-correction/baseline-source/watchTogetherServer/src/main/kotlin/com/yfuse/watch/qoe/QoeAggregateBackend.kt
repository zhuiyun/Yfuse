package com.yfuse.watch.qoe

import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeProtocol
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Stores only daily dimension counts. No individual event or client address is persisted. */
internal class QoeAggregateBackend private constructor(
    private val connection: Connection,
) : AutoCloseable {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS anonymous_qoe_daily (
                        day_utc TEXT NOT NULL,
                        dimensions_json TEXT NOT NULL,
                        sample_count INTEGER NOT NULL CHECK(sample_count > 0),
                        PRIMARY KEY(day_utc, dimensions_json)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    fun record(
        dayUtc: String,
        report: AnonymousPlaybackQoeReport,
    ) = synchronized(lock) {
        require(DAY.matches(dayUtc)) { "Invalid UTC day" }
        require(QoeProtocol.isValid(report)) { "Invalid anonymous QoE report" }
        connection
            .prepareStatement(
                """
                INSERT INTO anonymous_qoe_daily(day_utc, dimensions_json, sample_count)
                VALUES (?, ?, 1)
                ON CONFLICT(day_utc, dimensions_json)
                DO UPDATE SET sample_count = sample_count + 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, dayUtc)
                statement.setString(2, json.encodeToString(report))
                statement.executeUpdate()
            }
    }

    fun count(
        dayUtc: String,
        report: AnonymousPlaybackQoeReport,
    ): Long =
        synchronized(lock) {
            connection
                .prepareStatement(
                    "SELECT sample_count FROM anonymous_qoe_daily WHERE day_utc = ? AND dimensions_json = ?",
                ).use { statement ->
                    statement.setString(1, dayUtc)
                    statement.setString(2, json.encodeToString(report))
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
                }
        }

    override fun close() = synchronized(lock) { connection.close() }

    companion object {
        fun sqlite(file: File): QoeAggregateBackend {
            file.absoluteFile.parentFile?.mkdirs()
            return QoeAggregateBackend(open("jdbc:sqlite:${file.absoluteFile.path}"))
        }

        fun inMemory(): QoeAggregateBackend = QoeAggregateBackend(open("jdbc:sqlite::memory:"))

        private fun open(url: String): Connection {
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection(url).apply {
                createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            }
        }

        private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}
