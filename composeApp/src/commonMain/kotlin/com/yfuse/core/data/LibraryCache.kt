package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.HomeContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** A server-scoped disk snapshot and the time its source request last succeeded. */
data class LibraryCacheSnapshot(
    val content: HomeContent,
    /** Null only for the legacy cache shape, which did not persist freshness metadata. */
    val updatedAtEpochMs: Long?,
)

@Serializable
private data class PersistedLibraryCache(
    @SerialName("v") val version: Int,
    @SerialName("updatedAt") val updatedAtEpochMs: Long,
    @SerialName("content") val content: HomeContent,
)

/**
 * The last library page a server served, kept so a cold start has something to paint.
 *
 * The library is the launch screen for anyone who has connected a server, and it was
 * showing a skeleton for as long as the first request took — every single launch, for
 * content that had almost certainly not changed since the app was last closed. Cached
 * content goes on screen immediately and is replaced by the live response when it arrives,
 * so the wait costs nothing but a moment of slightly stale posters.
 *
 * Deliberately not a general-purpose cache: it has no TTL and is never authoritative. The
 * persisted success timestamp lets the UI disclose how old that first frame is, and whatever
 * the server says next always wins.
 */
class LibraryCache(private val settings: Settings) {
    private companion object {
        const val KEY_PREFIX = "library.cache."
        const val PERSISTED_VERSION = 2

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

    /**
     * Reads the current v2 envelope or the pre-freshness raw [HomeContent] shape.
     *
     * Legacy content intentionally returns a null timestamp. It remains useful for the first
     * frame, but callers must label it as cached with an unknown update time until a live request
     * succeeds and rewrites the entry in v2.
     */
    fun readSnapshot(serverId: String): LibraryCacheSnapshot? {
        val raw = settings.getStringOrNull(KEY_PREFIX + serverId) ?: return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            if ("v" in root) {
                val persisted = json.decodeFromString(PersistedLibraryCache.serializer(), raw)
                require(persisted.version == PERSISTED_VERSION) { "Unsupported library cache version" }
                require(persisted.updatedAtEpochMs >= 0L) { "Invalid library cache timestamp" }
                LibraryCacheSnapshot(
                    content = persisted.content,
                    updatedAtEpochMs = persisted.updatedAtEpochMs,
                )
            } else {
                LibraryCacheSnapshot(
                    content = json.decodeFromString(HomeContent.serializer(), raw),
                    updatedAtEpochMs = null,
                )
            }
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
        }.getOrNull()?.takeIf { !it.content.isEmpty }
    }

    /** Compatibility helper for consumers that only need the content body. */
    fun read(serverId: String): HomeContent? = readSnapshot(serverId)?.content

    /** Writes only successful live content; [updatedAtEpochMs] belongs to that same response. */
    fun write(serverId: String, content: HomeContent, updatedAtEpochMs: Long) {
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
            collections = content.collections,
            playlists = content.playlists,
        )
        runCatching {
            settings.putString(
                KEY_PREFIX + serverId,
                json.encodeToString(
                    PersistedLibraryCache.serializer(),
                    PersistedLibraryCache(
                        version = PERSISTED_VERSION,
                        updatedAtEpochMs = updatedAtEpochMs.coerceAtLeast(0L),
                        content = trimmed,
                    ),
                ),
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
