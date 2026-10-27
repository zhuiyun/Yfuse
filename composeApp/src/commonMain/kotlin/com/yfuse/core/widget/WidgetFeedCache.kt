package com.yfuse.core.widget

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal const val MAX_WIDGET_FEED_CACHE_BYTES = 4 * MAX_WIDGET_SOURCE_BYTES

/** Display snapshots live separately from executable widget configuration and backups. */
internal class WidgetFeedCache(
    private val persistence: WidgetPersistence,
) {
    @Serializable
    private data class Entry(
        val key: String,
        val items: List<WidgetMedia>,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val mutex = Mutex()

    suspend fun restore() {
        try {
            val raw = persistence.readFeedCache() ?: return
            if (raw.encodeToByteArray().size > MAX_WIDGET_FEED_CACHE_BYTES) return
            entries.value = widgetJson.decodeFromString<List<Entry>>(raw).takeLast(64)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A cache miss must not turn a valid home configuration into an error.
        }
    }

    fun peek(key: String): List<WidgetMedia> =
        entries.value
            .lastOrNull { it.key == key }
            ?.items
            .orEmpty()

    suspend fun save(
        key: String,
        items: List<WidgetMedia>,
    ) = mutex.withLock {
        var next = (entries.value.filterNot { it.key == key } + Entry(key, items.take(100))).takeLast(64)
        var encoded = widgetJson.encodeToString(next)
        while (encoded.encodeToByteArray().size > MAX_WIDGET_FEED_CACHE_BYTES && next.isNotEmpty()) {
            next = next.drop(1)
            encoded = widgetJson.encodeToString(next)
        }
        entries.value = next
        try {
            persistence.writeFeedCache(encoded)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Successfully fetched content is still usable if the disk is full.
        }
    }
}
