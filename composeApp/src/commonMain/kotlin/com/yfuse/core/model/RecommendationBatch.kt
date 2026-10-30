package com.yfuse.core.model

/** Prefer unseen entries, then the least recently shown, without changing source ranking. */
internal fun <T> recommendationBatch(
    candidates: List<T>,
    limit: Int,
    recentKeys: List<String>,
    key: (T) -> String,
): List<T> {
    if (limit <= 0) return emptyList()
    val unique = candidates.distinctBy(key)
    val positions = recentKeys.withIndex().associate { it.value to it.index }
    val ordered = unique.sortedBy { positions[key(it)] ?: -1 }
    // A short pool cannot supply new titles, but its lead card can still advance.
    val rotated =
        if (ordered.size in 2..limit && ordered.all { key(it) in positions }) {
            ordered.drop(1) + ordered.take(1)
        } else {
            ordered
        }
    return rotated.take(limit)
}

internal fun recordRecommendationExposure(
    recentKeys: List<String>,
    shownKeys: List<String>,
): List<String> = (recentKeys.filterNot { it in shownKeys } + shownKeys).distinct().takeLast(500)
