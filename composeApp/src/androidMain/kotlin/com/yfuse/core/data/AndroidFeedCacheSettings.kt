package com.yfuse.core.data

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

/** Invoked lazily on the cache worker. Old cache keys are removed only after a durable copy. */
fun androidFeedCacheSettings(context: Context): () -> Settings {
    val app = context.applicationContext
    val storage by lazy {
        val destination = app.getSharedPreferences("yfuse_feed_cache_v1", Context.MODE_PRIVATE)
        val legacy = app.getSharedPreferences("yfuse", Context.MODE_PRIVATE)
        val entries = legacy.all.filterKeys { it.startsWith("library.cache.") || it == "tmdb.home.cache.v1" }
        if (entries.isNotEmpty()) {
            val editor = destination.edit()
            entries.forEach { (key, value) ->
                if (!destination.contains(key) && value is String) editor.putString(key, value)
            }
            check(editor.commit()) { "Feed cache migration could not be persisted" }
            val cleanup = legacy.edit()
            entries.keys.forEach(cleanup::remove)
            cleanup.apply()
        }
        SharedPreferencesSettings(destination)
    }
    return { storage }
}
