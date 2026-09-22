@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.yfuse.core.model

import kotlin.time.Instant

/** One card per work, retaining the latest entry's exact item, source and resume position. */
internal fun deduplicatePlaybackHistory(items: List<MediaItem>): List<MediaItem> =
    deduplicatePlaybackHistory(items, { it }, { "" })

internal fun <T> deduplicatePlaybackHistory(
    entries: List<T>,
    itemOf: (T) -> MediaItem,
    serverIdOf: (T) -> String,
): List<T> {
    // Stable sorting retains the server's order when timestamps are missing or equal.
    val ordered =
        entries.sortedByDescending { entry ->
            itemOf(entry).lastPlayedDate?.let { runCatching { Instant.parse(it) }.getOrNull() }
        }
    val retained = mutableListOf<T>()
    for (entry in ordered) {
        if (retained.none { existing ->
                sameHistoryWork(itemOf(entry), serverIdOf(entry), itemOf(existing), serverIdOf(existing))
            }
        ) {
            retained += entry
        }
    }
    return retained
}

private fun sameHistoryWork(
    a: MediaItem,
    serverA: String,
    b: MediaItem,
    serverB: String,
): Boolean {
    if (serverA == serverB && a.id == b.id) return true
    val kindA = a.historyKind()
    if (kindA != b.historyKind()) return false
    if (serverA == serverB && a.historyWorkId() == b.historyWorkId()) return true

    // Episode provider IDs identify individual episodes, not the containing TV series.
    if (kindA != "series") {
        val idsA = a.historyProviderIds()
        val idsB = b.historyProviderIds()
        val sharedProviders = idsA.keys.intersect(idsB.keys)
        if (sharedProviders.isNotEmpty()) return sharedProviders.all { idsA[it] == idsB[it] }
    }

    // A title alone is not enough to merge remakes or unidentified items from other servers.
    val title = a.title.historyTitle()
    return title.isNotEmpty() && title == b.title.historyTitle() && a.year != null && a.year == b.year
}

private fun MediaItem.historyKind(): String =
    when (type.lowercase()) {
        "episode", "series" -> "series"
        else -> type.lowercase()
    }

private fun MediaItem.historyWorkId(): String =
    if (type.equals("Episode", ignoreCase = true)) posterItemId.ifBlank { id } else id

private fun MediaItem.historyProviderIds(): Map<String, String> =
    providerIds.entries
        .filter { it.key.lowercase() in setOf("tmdb", "imdb", "tvdb") && it.value.isNotBlank() }
        .associate { it.key.lowercase() to it.value.trim().lowercase() }

private fun String.historyTitle(): String = lowercase().filter { it.isLetterOrDigit() }
