package com.yfuse.feature.search

import com.yfuse.core.data.CrossServerMediaGroup

/** Applies relevance after grouping, so copy counts cannot push an exact title below a sequel. */
internal fun rankAggregatedSearch(
    groups: List<CrossServerMediaGroup>,
    query: String,
): List<CrossServerMediaGroup> {
    val needle = query.trim().lowercase()
    if (needle.isBlank()) return groups
    return groups.sortedBy { group ->
        val title =
            group.recommended.item.title
                .trim()
                .lowercase()
        when {
            title == needle -> 0
            title.startsWith(needle) -> 1
            needle in title -> 2
            else -> 3
        }
    }
}
