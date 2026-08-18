package com.yfuse.core.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable row store for offline metadata; progress updates never rewrite unrelated downloads. */
internal class OfflineMediaIndexStore(
    context: Context,
    private val json: Json,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE offline_media (" +
                "id TEXT PRIMARY KEY NOT NULL," +
                "payload TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL" +
                ")",
        )
        database.execSQL("CREATE INDEX offline_media_updated ON offline_media(updated_at DESC)")
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) = Unit

    fun load(): List<OfflineMedia> {
        val result = mutableListOf<OfflineMedia>()
        readableDatabase
            .query(
                "offline_media",
                arrayOf("payload"),
                null,
                null,
                null,
                null,
                "updated_at DESC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result += json.decodeFromString(OfflineMedia.serializer(), cursor.getString(0))
                }
            }
        return result
    }

    fun sync(
        previous: List<OfflineMedia>,
        current: List<OfflineMedia>,
    ) {
        val previousById = previous.associateBy(OfflineMedia::id)
        val currentIds = current.mapTo(HashSet(), OfflineMedia::id)
        writableDatabase.runInTransaction {
            previous.asSequence().filter { it.id !in currentIds }.forEach { removed ->
                delete("offline_media", "id = ?", arrayOf(removed.id))
            }
            current.asSequence().filter { previousById[it.id] != it }.forEach { item ->
                val values =
                    ContentValues(3).apply {
                        put("id", item.id)
                        put("payload", json.encodeToString(item))
                        put("updated_at", item.updatedAtEpochMs)
                    }
                insertWithOnConflict("offline_media", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    private inline fun SQLiteDatabase.runInTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "offline-media-index.db"
        const val DATABASE_VERSION = 1
    }
}
