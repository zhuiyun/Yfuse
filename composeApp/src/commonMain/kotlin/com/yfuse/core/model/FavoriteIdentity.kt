package com.yfuse.core.model

/** Keep the first concrete copy. Matching provider IDs are required; titles cannot identify remakes. */
internal fun deduplicateFavoriteItems(items: List<MediaItem>): List<MediaItem> {
    val retained = mutableListOf<MediaItem>()
    val knownIds = mutableListOf<Map<String, String>>()
    val byProvider = mutableMapOf<Triple<String, String, String>, MutableList<Int>>()
    val seenItems = mutableSetOf<String>()
    for (item in items) {
        if (!seenItems.add(item.id)) continue
        val type = item.type.lowercase()
        val ids =
            item.providerIds.entries
                .filter { it.key.lowercase() in setOf("tmdb", "imdb", "tvdb") && it.value.isNotBlank() }
                .associate { it.key.lowercase() to it.value.trim().lowercase() }
        val candidates =
            ids
                .flatMap { (provider, value) ->
                    byProvider[Triple(type, provider, value)].orEmpty()
                }.distinct()
        val duplicate =
            candidates.any { index ->
                // A match on one provider must not hide a conflict on another provider.
                knownIds[index].all { (provider, value) -> ids[provider]?.let { it == value } != false }
            }
        if (duplicate) continue
        val index = retained.size
        retained += item
        knownIds += ids
        ids.forEach { (provider, value) ->
            byProvider.getOrPut(Triple(type, provider, value)) { mutableListOf() }.add(index)
        }
    }
    return retained
}
