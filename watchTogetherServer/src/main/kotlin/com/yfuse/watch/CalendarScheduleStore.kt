package com.yfuse.watch

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

/**
 * Durable source of truth for published calendar revisions.
 *
 * The collector replaces a complete revision in one transaction and flips the singleton current
 * pointer only after every series, episode and evidence row has been stored. Readers therefore see
 * either the previous verified publication or the complete next one, never a partial refresh.
 */
internal interface CalendarScheduleStore : AutoCloseable {
    fun current(): CalendarPublication?

    /** Returns false when this exact revision is already current. */
    fun replace(publication: CalendarPublication): Boolean

    override fun close() = Unit

    companion object {
        fun sqlite(file: File): CalendarScheduleStore = SqliteCalendarScheduleStore.open(file)

        fun inMemory(): CalendarScheduleStore = SqliteCalendarScheduleStore.openMemory()
    }
}

internal object NoOpCalendarScheduleStore : CalendarScheduleStore {
    override fun current(): CalendarPublication? = null

    override fun replace(publication: CalendarPublication): Boolean = false
}

private class SqliteCalendarScheduleStore private constructor(
    private val connection: Connection,
    private val fileBacked: Boolean,
) : CalendarScheduleStore {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }

    init {
        synchronized(lock) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA busy_timeout = 5000")
                if (fileBacked) {
                    statement.execute("PRAGMA journal_mode = WAL")
                    statement.execute("PRAGMA synchronous = FULL")
                    statement.execute("PRAGMA wal_autocheckpoint = 1000")
                }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_publications (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        revision TEXT NOT NULL UNIQUE,
                        generated_at TEXT NOT NULL,
                        stored_at_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_series (
                        publication_id INTEGER NOT NULL
                            REFERENCES calendar_publications(id) ON DELETE CASCADE,
                        tmdb_id INTEGER NOT NULL CHECK(tmdb_id > 0),
                        season_number INTEGER NOT NULL CHECK(season_number > 0),
                        title TEXT NOT NULL,
                        poster_path TEXT,
                        air_time TEXT NOT NULL,
                        time_zone_id TEXT NOT NULL,
                        platforms_json TEXT NOT NULL,
                        access_tier TEXT NOT NULL,
                        source_url TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        authority TEXT NOT NULL,
                        confidence INTEGER NOT NULL CHECK(confidence BETWEEN 0 AND 100),
                        PRIMARY KEY(publication_id, tmdb_id, season_number)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_episodes (
                        publication_id INTEGER NOT NULL,
                        tmdb_id INTEGER NOT NULL,
                        season_number INTEGER NOT NULL,
                        episode_number INTEGER NOT NULL CHECK(episode_number > 0),
                        air_date TEXT NOT NULL,
                        PRIMARY KEY(
                            publication_id,
                            tmdb_id,
                            season_number,
                            episode_number
                        ),
                        FOREIGN KEY(publication_id, tmdb_id, season_number)
                            REFERENCES calendar_series(
                                publication_id,
                                tmdb_id,
                                season_number
                            ) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_evidence (
                        publication_id INTEGER NOT NULL,
                        tmdb_id INTEGER NOT NULL,
                        season_number INTEGER NOT NULL,
                        position INTEGER NOT NULL CHECK(position >= 0),
                        type TEXT NOT NULL,
                        publisher TEXT NOT NULL,
                        source_url TEXT NOT NULL,
                        captured_at TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        extraction_method TEXT NOT NULL,
                        PRIMARY KEY(
                            publication_id,
                            tmdb_id,
                            season_number,
                            position
                        ),
                        FOREIGN KEY(publication_id, tmdb_id, season_number)
                            REFERENCES calendar_series(
                                publication_id,
                                tmdb_id,
                                season_number
                            ) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_current (
                        singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
                        publication_id INTEGER NOT NULL UNIQUE
                            REFERENCES calendar_publications(id) ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_calendar_episodes_air_date
                    ON calendar_episodes(air_date, tmdb_id, season_number, episode_number)
                    """.trimIndent(),
                )
            }
        }
    }

    override fun current(): CalendarPublication? =
        synchronized(lock) {
            val header = currentHeader() ?: return@synchronized null
            val schedules =
                connection
                    .prepareStatement(
                        """
                        SELECT
                            tmdb_id,
                            season_number,
                            title,
                            poster_path,
                            air_time,
                            time_zone_id,
                            platforms_json,
                            access_tier,
                            source_url,
                            updated_at,
                            authority,
                            confidence
                        FROM calendar_series
                        WHERE publication_id = ?
                        ORDER BY tmdb_id, season_number
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, header.id)
                        statement.executeQuery().use { result ->
                            buildList {
                                while (result.next()) {
                                    val tmdbId = result.getInt("tmdb_id")
                                    val seasonNumber = result.getInt("season_number")
                                    add(
                                        CalendarSeries(
                                            tmdbId = tmdbId,
                                            seasonNumber = seasonNumber,
                                            title = result.getString("title"),
                                            posterPath = result.getString("poster_path"),
                                            airTime = result.getString("air_time"),
                                            timeZoneId = result.getString("time_zone_id"),
                                            platforms =
                                                json.decodeFromString(
                                                    result.getString("platforms_json"),
                                                ),
                                            accessTier = result.getString("access_tier"),
                                            sourceUrl = result.getString("source_url"),
                                            revision = header.revision,
                                            updatedAt = result.getString("updated_at"),
                                            authority = result.getString("authority"),
                                            confidence = result.getInt("confidence"),
                                            evidence = readEvidence(header.id, tmdbId, seasonNumber),
                                            episodes = readEpisodes(header.id, tmdbId, seasonNumber),
                                        ),
                                    )
                                }
                            }
                        }
                    }
            CalendarPublication(
                revision = header.revision,
                generatedAt = header.generatedAt,
                schedules = schedules,
            ).also(::validateCalendarPublication)
        }

    override fun replace(publication: CalendarPublication): Boolean {
        validateCalendarPublication(publication)
        return synchronized(lock) {
            val current = currentHeader()
            if (current?.revision == publication.revision) return@synchronized false
            require(
                current == null ||
                    calendarServerRevisionIsAtLeast(publication.revision, current.revision),
            ) {
                "Calendar revision rollback rejected: ${publication.revision} < ${current?.revision}"
            }

            val previousAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                connection
                    .prepareStatement(
                        """
                        INSERT INTO calendar_publications(revision, generated_at, stored_at_ms)
                        VALUES (?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, publication.revision)
                        statement.setString(2, publication.generatedAt)
                        statement.setLong(3, System.currentTimeMillis())
                        statement.executeUpdate()
                    }
                val publicationId = publicationId(publication.revision)
                publication.schedules.forEach { schedule ->
                    insertSeries(publicationId, schedule)
                    insertEpisodes(publicationId, schedule)
                    insertEvidence(publicationId, schedule)
                }
                connection
                    .prepareStatement(
                        """
                        INSERT INTO calendar_current(singleton, publication_id)
                        VALUES (1, ?)
                        ON CONFLICT(singleton) DO UPDATE SET publication_id = excluded.publication_id
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setLong(1, publicationId)
                        statement.executeUpdate()
                    }
                pruneOldPublications()
                connection.commit()
                true
            } catch (failure: Exception) {
                runCatching(connection::rollback)
                throw failure
            } finally {
                connection.autoCommit = previousAutoCommit
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            connection.close()
        }
    }

    private fun insertSeries(
        publicationId: Long,
        schedule: CalendarSeries,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO calendar_series(
                    publication_id,
                    tmdb_id,
                    season_number,
                    title,
                    poster_path,
                    air_time,
                    time_zone_id,
                    platforms_json,
                    access_tier,
                    source_url,
                    updated_at,
                    authority,
                    confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, publicationId)
                statement.setInt(2, schedule.tmdbId)
                statement.setInt(3, schedule.seasonNumber)
                statement.setString(4, schedule.title)
                statement.setString(5, schedule.posterPath)
                statement.setString(6, schedule.airTime)
                statement.setString(7, schedule.timeZoneId)
                statement.setString(8, json.encodeToString(schedule.platforms))
                statement.setString(9, schedule.accessTier)
                statement.setString(10, schedule.sourceUrl)
                statement.setString(11, schedule.updatedAt)
                statement.setString(12, schedule.authority)
                statement.setInt(13, schedule.confidence)
                statement.executeUpdate()
            }
    }

    private fun insertEpisodes(
        publicationId: Long,
        schedule: CalendarSeries,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO calendar_episodes(
                    publication_id,
                    tmdb_id,
                    season_number,
                    episode_number,
                    air_date
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                schedule.episodes.forEach { episode ->
                    statement.setLong(1, publicationId)
                    statement.setInt(2, schedule.tmdbId)
                    statement.setInt(3, schedule.seasonNumber)
                    statement.setInt(4, episode.episodeNumber)
                    statement.setString(5, episode.airDate)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun insertEvidence(
        publicationId: Long,
        schedule: CalendarSeries,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO calendar_evidence(
                    publication_id,
                    tmdb_id,
                    season_number,
                    position,
                    type,
                    publisher,
                    source_url,
                    captured_at,
                    content_hash,
                    extraction_method
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                schedule.evidence.forEachIndexed { position, evidence ->
                    statement.setLong(1, publicationId)
                    statement.setInt(2, schedule.tmdbId)
                    statement.setInt(3, schedule.seasonNumber)
                    statement.setInt(4, position)
                    statement.setString(5, evidence.type)
                    statement.setString(6, evidence.publisher)
                    statement.setString(7, evidence.sourceUrl)
                    statement.setString(8, evidence.capturedAt)
                    statement.setString(9, evidence.contentHash)
                    statement.setString(10, evidence.extractionMethod)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
    }

    private fun readEpisodes(
        publicationId: Long,
        tmdbId: Int,
        seasonNumber: Int,
    ): List<CalendarEpisode> =
        connection
            .prepareStatement(
                """
                SELECT episode_number, air_date
                FROM calendar_episodes
                WHERE publication_id = ? AND tmdb_id = ? AND season_number = ?
                ORDER BY episode_number
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, publicationId)
                statement.setInt(2, tmdbId)
                statement.setInt(3, seasonNumber)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                CalendarEpisode(
                                    episodeNumber = result.getInt("episode_number"),
                                    airDate = result.getString("air_date"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun readEvidence(
        publicationId: Long,
        tmdbId: Int,
        seasonNumber: Int,
    ): List<CalendarEvidence> =
        connection
            .prepareStatement(
                """
                SELECT
                    type,
                    publisher,
                    source_url,
                    captured_at,
                    content_hash,
                    extraction_method
                FROM calendar_evidence
                WHERE publication_id = ? AND tmdb_id = ? AND season_number = ?
                ORDER BY position
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, publicationId)
                statement.setInt(2, tmdbId)
                statement.setInt(3, seasonNumber)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                CalendarEvidence(
                                    type = result.getString("type"),
                                    publisher = result.getString("publisher"),
                                    sourceUrl = result.getString("source_url"),
                                    capturedAt = result.getString("captured_at"),
                                    contentHash = result.getString("content_hash"),
                                    extractionMethod = result.getString("extraction_method"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun publicationId(revision: String): Long =
        connection
            .prepareStatement(
                "SELECT id FROM calendar_publications WHERE revision = ?",
            ).use { statement ->
                statement.setString(1, revision)
                statement.executeQuery().use { result ->
                    check(result.next()) { "Stored calendar publication is missing" }
                    result.getLong("id")
                }
            }

    private fun currentHeader(): PublicationHeader? =
        connection
            .createStatement()
            .use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT publication.id, publication.revision, publication.generated_at
                        FROM calendar_current AS pointer
                        JOIN calendar_publications AS publication
                            ON publication.id = pointer.publication_id
                        WHERE pointer.singleton = 1
                        """.trimIndent(),
                    ).use { result ->
                        if (!result.next()) {
                            null
                        } else {
                            PublicationHeader(
                                id = result.getLong("id"),
                                revision = result.getString("revision"),
                                generatedAt = result.getString("generated_at"),
                            )
                        }
                    }
            }

    private fun pruneOldPublications() {
        connection
            .prepareStatement(
                """
                DELETE FROM calendar_publications
                WHERE id NOT IN (
                    SELECT id
                    FROM calendar_publications
                    ORDER BY id DESC
                    LIMIT ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, RETAINED_REVISIONS)
                statement.executeUpdate()
            }
    }

    private data class PublicationHeader(
        val id: Long,
        val revision: String,
        val generatedAt: String,
    )

    companion object {
        private const val RETAINED_REVISIONS = 12

        fun open(file: File): SqliteCalendarScheduleStore {
            val parent =
                requireNotNull(file.absoluteFile.parentFile) {
                    "Calendar DB needs a parent directory"
                }
            require((parent.isDirectory || parent.mkdirs()) && parent.canWrite()) {
                "Calendar DB parent directory is not writable: $parent"
            }
            val store =
                SqliteCalendarScheduleStore(
                    connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"),
                    fileBacked = true,
                )
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            }
            return store
        }

        fun openMemory(): SqliteCalendarScheduleStore =
            SqliteCalendarScheduleStore(
                connection = DriverManager.getConnection("jdbc:sqlite::memory:"),
                fileBacked = false,
            )
    }
}

private fun calendarServerRevisionIsAtLeast(
    candidate: String,
    existing: String,
): Boolean {
    fun parse(value: String): Pair<LocalDate, Int>? {
        val marker = value.lastIndexOf("-r")
        if (marker <= 0) return null
        val date = runCatching { LocalDate.parse(value.substring(0, marker)) }.getOrNull() ?: return null
        val sequence = value.substring(marker + 2).toIntOrNull() ?: return null
        return date to sequence
    }

    val next = parse(candidate)
    val current = parse(existing)
    return if (next != null && current != null) {
        next.first > current.first ||
            next.first == current.first && next.second >= current.second
    } else {
        candidate >= existing
    }
}
