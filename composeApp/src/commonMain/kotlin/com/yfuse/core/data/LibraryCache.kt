package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.HomeContent
import kotlinx.serialization.json.Json

/**
 * The last library page a server served, kept so a cold start has something to paint.
 *
 * The library is the launch screen for anyone who has connected a server, and it was
 * showing a skeleton for as long as the first request took — every single launch, for
 * content that had almost certainly not changed since the app was last closed. Cached
 * content goes on screen immediately and is replaced by the live response when it arrives,
 * so the wait costs nothing but a moment of slightly stale posters.
 *
 * Deliberately not a general-purpose cache: it has no TTL and is never read except to fill
 * that first frame. Whatever the server says next always wins, so there is no staleness
 * window to reason about — only a head start.
 */
class LibraryCache(private val settings: Settings) {
    private companion object {
        const val KEY_PREFIX = "library.cache."

        /**
         * Rows are trimmed before storing. Settings is `SharedPreferences` on Android — a
         * synchronously-parsed XML file loaded during app startup, which is exactly the
         * thing this cache exists to keep fast. A screenful is all the first frame needs;
         * the live response restores the rest a moment later.
         */
        const val MAX_ITEMS_PER_ROW = 20
        const val MAX_ROWS = 12
        const val MAX_FEATURED = 8
        const val MAX_RESUME = 12
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun read(serverId: String): HomeContent? {
        val raw = settings.getStringOrNull(KEY_PREFIX + serverId) ?: return null
        return runCatching {
            json.decodeFromString(HomeContent.serializer(), raw)
        }.onFailure {
            // A shape change between versions lands here. The entry is dropped rather than
            // retried on every launch; the next successful load writes a readable one.
            settings.remove(KEY_PREFIX + serverId)
            AppLog.warning(
                category = "feature.library",
                event = "cache_unreadable",
                message = "Cached library content could not be read and was discarded",
                throwable = it,
            )
        }.getOrNull()?.takeIf { !it.isEmpty }
    }

    fun write(serverId: String, content: HomeContent) {
        if (content.isEmpty) {
            settings.remove(KEY_PREFIX + serverId)
            return
        }
        val trimmed = HomeContent(
            featured = content.featured.take(MAX_FEATURED),
            resume = content.resume.take(MAX_RESUME),
            rows = content.rows.take(MAX_ROWS).map { row ->
                row.copy(items = row.items.take(MAX_ITEMS_PER_ROW))
            },
            counts = content.counts,
        )
        runCatching {
            settings.putString(
                KEY_PREFIX + serverId,
                json.encodeToString(HomeContent.serializer(), trimmed),
            )
        }.onFailure {
            AppLog.warning(
                category = "feature.library",
                event = "cache_write_failed",
                message = "Library content could not be cached",
                throwable = it,
            )
        }
    }

    /** Dropped along with the server it belongs to, so a re-add starts clean. */
    fun clear(serverId: String) {
        settings.remove(KEY_PREFIX + serverId)
    }

    /**
     * Removes snapshots whose canonical server no longer exists. Alias ids intentionally do
     * not count: they resolve routes, but their cached home belongs to an obsolete connection.
     */
    internal fun clearOrphans(validServerIds: Set<String>): Int {
        val orphanKeys = settings.keys.filter { key ->
            key.startsWith(KEY_PREFIX) &&
                key.removePrefix(KEY_PREFIX).let { it.isNotEmpty() && it !in validServerIds }
        }
        orphanKeys.forEach(settings::remove)
        return orphanKeys.size
    }
}
