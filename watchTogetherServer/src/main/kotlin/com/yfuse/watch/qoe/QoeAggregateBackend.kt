package com.yfuse.watch.qoe

import com.yfuse.watch.protocol.AnonymousPlaybackQoeReport
import com.yfuse.watch.protocol.QoeProtocol
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Stores only daily dimension counts. No individual event or client address is persisted.
 *
 * Every dimension but the app version is a closed enum or bucket, so the version is what could
 * make the table grow without bound: it is kept only in the shape of a release name and folded
 * into [OTHER_APP_VERSION] otherwise, and a day holds at most [maxRowsPerDay] distinct rows. Past
 * that, combinations already seen keep counting while new ones are dropped.
 */
internal class QoeAggregateBackend private constructor(
    private val connection: Connection,
    private val maxRowsPerDay: Int = DEFAULT_MAX_ROWS_PER_DAY,
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

    /** Returns false when the day is full and this combination was not already counted. */
    fun record(
        dayUtc: String,
        report: AnonymousPlaybackQoeReport,
    ): Boolean =
        synchronized(lock) {
            require(DAY.matches(dayUtc)) { "Invalid UTC day" }
            require(QoeProtocol.isValid(report)) { "Invalid anonymous QoE report" }
            val dimensions = json.encodeToString(report.withBoundedAppVersion())
            val counted =
                connection
                    .prepareStatement(
                        "UPDATE anonymous_qoe_daily SET sample_count = sample_count + 1 " +
                            "WHERE day_utc = ? AND dimensions_json = ?",
                    ).use { statement ->
                        statement.setString(1, dayUtc)
                        statement.setString(2, dimensions)
                        statement.executeUpdate()
                    }
            if (counted > 0) return@synchronized true
            if (rowsOn(dayUtc) >= maxRowsPerDay) return@synchronized false
            connection
                .prepareStatement(
                    "INSERT INTO anonymous_qoe_daily(day_utc, dimensions_json, sample_count) VALUES (?, ?, 1)",
                ).use { statement ->
                    statement.setString(1, dayUtc)
                    statement.setString(2, dimensions)
                    statement.executeUpdate()
                }
            true
        }

    private fun rowsOn(dayUtc: String): Int =
        connection
            .prepareStatement("SELECT COUNT(*) FROM anonymous_qoe_daily WHERE day_utc = ?")
            .use { statement ->
                statement.setString(1, dayUtc)
                statement.executeQuery().use { result -> if (result.next()) result.getInt(1) else 0 }
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
                    statement.setString(2, json.encodeToString(report.withBoundedAppVersion()))
                    statement.executeQuery().use { result -> if (result.next()) result.getLong(1) else 0L }
                }
        }

    override fun close() = synchronized(lock) { connection.close() }

    companion object {
        fun sqlite(file: File): QoeAggregateBackend {
            file.absoluteFile.parentFile?.mkdirs()
            return QoeAggregateBackend(open("jdbc:sqlite:${file.absoluteFile.path}"))
        }

        fun inMemory(maxRowsPerDay: Int = DEFAULT_MAX_ROWS_PER_DAY): QoeAggregateBackend =
            QoeAggregateBackend(open("jdbc:sqlite::memory:"), maxRowsPerDay)

        /** A release name such as `1.1.5`; anything else is one bucket rather than its own rows. */
        internal fun boundedAppVersion(raw: String): String = raw.takeIf(RELEASE_VERSION::matches) ?: OTHER_APP_VERSION

        private fun AnonymousPlaybackQoeReport.withBoundedAppVersion(): AnonymousPlaybackQoeReport =
            boundedAppVersion(appVersion).let { if (it == appVersion) this else copy(appVersion = it) }

        private fun open(url: String): Connection {
            Class.forName("org.sqlite.JDBC")
            return DriverManager.getConnection(url).apply {
                createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            }
        }

        private val DAY = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val RELEASE_VERSION = Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,4}")
        internal const val OTHER_APP_VERSION = "other"

        /** Far above the combinations real devices produce in a day, small enough to scan quickly. */
        private const val DEFAULT_MAX_ROWS_PER_DAY = 20_000
    }
}
