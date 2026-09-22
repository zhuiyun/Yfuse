package com.yfuse.core.offline

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
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
        createMetadata(database)
    }

    override fun onUpgrade(
        database: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        if (oldVersion < 2) createMetadata(database)
    }

    private fun createMetadata(database: SQLiteDatabase) {
        database.execSQL("CREATE TABLE IF NOT EXISTS offline_metadata (name TEXT PRIMARY KEY NOT NULL)")
    }

    fun migrationComplete(): Boolean =
        readableDatabase
            .rawQuery(
                "SELECT name FROM offline_metadata WHERE name = 'v1_imported'",
                null,
            ).use { it.moveToFirst() }

    fun migrateLegacy(items: List<OfflineMedia>) {
        writableDatabase.runInTransaction {
            items.forEach { insertItem(it) }
            execSQL("INSERT OR IGNORE INTO offline_metadata(name) VALUES ('v1_imported')")
        }
    }

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
        val removed = previous.filter { it.id !in currentIds }
        val changed = current.filter { previousById[it.id] != it }
        if (removed.isEmpty() && changed.isEmpty()) return
        writableDatabase.runInTransaction {
            removed.forEach { removed ->
                delete("offline_media", "id = ?", arrayOf(removed.id))
            }
            changed.forEach { item ->
                insertItem(item)
            }
        }
    }

    private fun SQLiteDatabase.insertItem(item: OfflineMedia) {
        val values =
            ContentValues(3).apply {
                put("id", item.id)
                put("payload", json.encodeToString(item))
                put("updated_at", item.updatedAtEpochMs)
            }
        if (insertWithOnConflict("offline_media", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1L) {
            throw SQLiteException("离线下载索引写入失败")
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
        const val DATABASE_VERSION = 2
    }
}
