package com.yfuse.core.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.yfuse.core.model.CalendarDay
import com.yfuse.core.model.CalendarEntry
import com.yfuse.core.model.CalendarSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Credential-free SQLite index for the airing calendar.
 *
 * Emby poster URLs carry api_key query parameters, so they are stripped before serialization and
 * rebuilt by AiringCalendarRepository from the active ServerRegistry after every read.
 */
class AndroidCalendarLocalStore(
    context: Context,
) : CalendarLocalStore {
    private val helper = CalendarDatabase(context.applicationContext)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    override suspend fun readCalendar(
        fromDate: String,
        toDate: String,
        scope: String,
    ): CalendarLocalSnapshot? =
        withContext(Dispatchers.IO) {
            val db = helper.readableDatabase
            val entries =
                db.query(
                    TABLE_EVENTS,
                    arrayOf(COL_ENTRY_JSON),
                    "$COL_AIR_DATE BETWEEN ? AND ?",
                    arrayOf(fromDate, toDate),
                    null,
                    null,
                    "$COL_AIR_DATE ASC, $COL_TMDB_ID ASC",
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            val raw = cursor.getString(cursor.getColumnIndexOrThrow(COL_ENTRY_JSON))
                            runCatching {
                                json.decodeFromString(CalendarEntry.serializer(), raw)
                            }.getOrNull()?.let(::add)
                        }
                    }
                }
            val syncState = db.readSyncState(scope)
            if (entries.isEmpty() && syncState == null) return@withContext null
            CalendarLocalSnapshot(
                days =
                    entries
                        .groupBy { it.episode.airDate }
                        .toSortedMap()
                        .map { (date, dayEntries) -> CalendarDay(date, dayEntries) },
                syncState = syncState,
            )
        }

    override suspend fun replaceCalendarWindow(
        fromDate: String,
        toDate: String,
        scope: String,
        days: List<CalendarDay>,
        scheduleSyncedAtEpochMs: Long,
        resourcesCheckedAtEpochMs: Long,
        seriesScope: Set<Int>?,
    ) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.transaction {
                val ids = seriesScope?.filter { it > 0 }?.distinct().orEmpty()
                val scopedClause =
                    if (seriesScope == null) {
                        "$COL_AIR_DATE BETWEEN ? AND ?"
                    } else if (ids.isEmpty()) {
                        null
                    } else {
                        "$COL_AIR_DATE BETWEEN ? AND ? AND $COL_TMDB_ID IN (${ids.joinToString { "?" }})"
                    }
                val scopedArgs =
                    if (seriesScope == null) {
                        arrayOf(fromDate, toDate)
                    } else {
                        arrayOf(fromDate, toDate, *ids.map(Int::toString).toTypedArray())
                    }
                if (scopedClause != null) {
                    execSQL(
                        "DELETE FROM $TABLE_RESOURCES WHERE $COL_MEDIA_KEY IN " +
                            "(SELECT $COL_MEDIA_KEY FROM $TABLE_EVENTS WHERE $scopedClause)",
                        scopedArgs,
                    )
                    delete(TABLE_EVENTS, scopedClause, scopedArgs)
                }

                days.flatMap(CalendarDay::entries).forEach { original ->
                    val checkedAt = original.availabilityCheckedAtEpochMs ?: resourcesCheckedAtEpochMs
                    val entry =
                        original.copy(
                            sources = original.sources.map(::withoutCredentials),
                            availabilityCheckedAtEpochMs = checkedAt,
                            availabilityStale = false,
                        )
                    insertWithOnConflict(
                        TABLE_EVENTS,
                        null,
                        ContentValues().apply {
                            put(COL_MEDIA_KEY, entry.episode.mediaKey)
                            put(COL_AIR_DATE, entry.episode.airDate)
                            put(COL_TMDB_ID, entry.episode.showTmdbId)
                            put(COL_ENTRY_JSON, json.encodeToString(CalendarEntry.serializer(), entry))
                            put(COL_SCHEDULE_SYNCED_AT, scheduleSyncedAtEpochMs)
                            put(COL_RESOURCE_CHECKED_AT, checkedAt)
                        },
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    entry.sources.forEach { source ->
                        insertWithOnConflict(
                            TABLE_RESOURCES,
                            null,
                            ContentValues().apply {
                                put(COL_MEDIA_KEY, entry.episode.mediaKey)
                                put(COL_SERVER_ID, source.serverId)
                                put(COL_SOURCE_JSON, json.encodeToString(CalendarSource.serializer(), source))
                                put(COL_RESOURCE_CHECKED_AT, checkedAt)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                        source.seriesItemId?.takeIf(String::isNotBlank)?.let { seriesItemId ->
                            insertBinding(
                                CalendarSeriesBinding(
                                    serverId = source.serverId,
                                    tmdbId = entry.episode.showTmdbId,
                                    seriesItemId = seriesItemId,
                                    title = entry.episode.showTitle,
                                    updatedAtEpochMs = checkedAt,
                                ),
                            )
                        }
                    }
                }
                writeSyncState(
                    CalendarSyncState(
                        scope = scope,
                        scheduleSyncedAtEpochMs = scheduleSyncedAtEpochMs,
                        resourcesSyncedAtEpochMs = resourcesCheckedAtEpochMs,
                        lastAttemptAtEpochMs = resourcesCheckedAtEpochMs,
                    ),
                )
            }
        }
    }

    override suspend fun readBindings(
        serverId: String,
        tmdbIds: Set<Int>,
    ): Map<Int, String> {
        val ids = tmdbIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            helper.readableDatabase
                .query(
                    TABLE_BINDINGS,
                    arrayOf(COL_TMDB_ID, COL_SERIES_ITEM_ID),
                    "$COL_SERVER_ID = ? AND $COL_TMDB_ID IN (${ids.joinToString { "?" }})",
                    arrayOf(serverId, *ids.map(Int::toString).toTypedArray()),
                    null,
                    null,
                    null,
                ).use { cursor ->
                    buildMap {
                        while (cursor.moveToNext()) {
                            put(cursor.getInt(0), cursor.getString(1))
                        }
                    }
                }
        }
    }

    override suspend fun upsertBindings(bindings: List<CalendarSeriesBinding>) {
        if (bindings.isEmpty()) return
        withContext(Dispatchers.IO) {
            helper.writableDatabase.transaction {
                bindings.forEach(::insertBinding)
            }
        }
    }

    override suspend fun recordSyncFailure(
        scope: String,
        attemptedAtEpochMs: Long,
        message: String?,
    ) {
        withContext(Dispatchers.IO) {
            helper.writableDatabase.transaction {
                val previous = readSyncState(scope)
                writeSyncState(
                    CalendarSyncState(
                        scope = scope,
                        scheduleSyncedAtEpochMs = previous?.scheduleSyncedAtEpochMs ?: 0L,
                        resourcesSyncedAtEpochMs = previous?.resourcesSyncedAtEpochMs ?: 0L,
                        lastAttemptAtEpochMs = attemptedAtEpochMs,
                        lastError = message?.take(MAX_ERROR_CHARS),
                    ),
                )
            }
        }
    }

    private fun SQLiteDatabase.insertBinding(binding: CalendarSeriesBinding) {
        insertWithOnConflict(
            TABLE_BINDINGS,
            null,
            ContentValues().apply {
                put(COL_SERVER_ID, binding.serverId)
                put(COL_TMDB_ID, binding.tmdbId)
                put(COL_SERIES_ITEM_ID, binding.seriesItemId)
                put(COL_TITLE, binding.title)
                put(COL_UPDATED_AT, binding.updatedAtEpochMs)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun SQLiteDatabase.readSyncState(scope: String): CalendarSyncState? =
        query(
            TABLE_SYNC_STATE,
            arrayOf(
                COL_SCHEDULE_SYNCED_AT,
                COL_RESOURCES_SYNCED_AT,
                COL_LAST_ATTEMPT_AT,
                COL_LAST_ERROR,
            ),
            "$COL_SCOPE = ?",
            arrayOf(scope),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            CalendarSyncState(
                scope = scope,
                scheduleSyncedAtEpochMs = cursor.getLong(0),
                resourcesSyncedAtEpochMs = cursor.getLong(1),
                lastAttemptAtEpochMs = cursor.getLong(2),
                lastError = cursor.nullableString(3),
            )
        }

    private fun SQLiteDatabase.writeSyncState(state: CalendarSyncState) {
        insertWithOnConflict(
            TABLE_SYNC_STATE,
            null,
            ContentValues().apply {
                put(COL_SCOPE, state.scope)
                put(COL_SCHEDULE_SYNCED_AT, state.scheduleSyncedAtEpochMs)
                put(COL_RESOURCES_SYNCED_AT, state.resourcesSyncedAtEpochMs)
                put(COL_LAST_ATTEMPT_AT, state.lastAttemptAtEpochMs)
                put(COL_LAST_ERROR, state.lastError)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun withoutCredentials(source: CalendarSource): CalendarSource = source.copy(posterUrl = null)

    private class CalendarDatabase(
        context: Context,
    ) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_EVENTS (
                    $COL_MEDIA_KEY TEXT PRIMARY KEY NOT NULL,
                    $COL_AIR_DATE TEXT NOT NULL,
                    $COL_TMDB_ID INTEGER NOT NULL,
                    $COL_ENTRY_JSON TEXT NOT NULL,
                    $COL_SCHEDULE_SYNCED_AT INTEGER NOT NULL,
                    $COL_RESOURCE_CHECKED_AT INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX calendar_events_date_idx ON $TABLE_EVENTS($COL_AIR_DATE)")
            db.execSQL("CREATE INDEX calendar_events_tmdb_idx ON $TABLE_EVENTS($COL_TMDB_ID)")
            db.execSQL(
                """
                CREATE TABLE $TABLE_BINDINGS (
                    $COL_SERVER_ID TEXT NOT NULL,
                    $COL_TMDB_ID INTEGER NOT NULL,
                    $COL_SERIES_ITEM_ID TEXT NOT NULL,
                    $COL_TITLE TEXT NOT NULL,
                    $COL_UPDATED_AT INTEGER NOT NULL,
                    PRIMARY KEY ($COL_SERVER_ID, $COL_TMDB_ID)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_RESOURCES (
                    $COL_MEDIA_KEY TEXT NOT NULL,
                    $COL_SERVER_ID TEXT NOT NULL,
                    $COL_SOURCE_JSON TEXT NOT NULL,
                    $COL_RESOURCE_CHECKED_AT INTEGER NOT NULL,
                    PRIMARY KEY ($COL_MEDIA_KEY, $COL_SERVER_ID),
                    FOREIGN KEY ($COL_MEDIA_KEY) REFERENCES $TABLE_EVENTS($COL_MEDIA_KEY) ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE $TABLE_SYNC_STATE (
                    $COL_SCOPE TEXT PRIMARY KEY NOT NULL,
                    $COL_SCHEDULE_SYNCED_AT INTEGER NOT NULL,
                    $COL_RESOURCES_SYNCED_AT INTEGER NOT NULL,
                    $COL_LAST_ATTEMPT_AT INTEGER NOT NULL,
                    $COL_LAST_ERROR TEXT
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(
            db: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = Unit
    }

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val DATABASE_NAME = "airing-calendar.db"
        const val DATABASE_VERSION = 1
        const val MAX_ERROR_CHARS = 500

        const val TABLE_EVENTS = "calendar_events"
        const val TABLE_BINDINGS = "series_bindings"
        const val TABLE_RESOURCES = "episode_resources"
        const val TABLE_SYNC_STATE = "calendar_sync_state"

        const val COL_MEDIA_KEY = "media_key"
        const val COL_AIR_DATE = "air_date"
        const val COL_TMDB_ID = "tmdb_id"
        const val COL_ENTRY_JSON = "entry_json"
        const val COL_SCHEDULE_SYNCED_AT = "schedule_synced_at"
        const val COL_RESOURCE_CHECKED_AT = "resource_checked_at"
        const val COL_SERVER_ID = "server_id"
        const val COL_SERIES_ITEM_ID = "series_item_id"
        const val COL_SOURCE_JSON = "source_json"
        const val COL_TITLE = "title"
        const val COL_UPDATED_AT = "updated_at"
        const val COL_SCOPE = "scope"
        const val COL_RESOURCES_SYNCED_AT = "resources_synced_at"
        const val COL_LAST_ATTEMPT_AT = "last_attempt_at"
        const val COL_LAST_ERROR = "last_error"
    }
}
