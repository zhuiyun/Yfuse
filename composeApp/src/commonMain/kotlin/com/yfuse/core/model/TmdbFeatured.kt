package com.yfuse.core.model

import com.yfuse.core.util.isoDateDaysBefore
import com.yfuse.core.util.isoEpochDay

/** Shared by the carousel and runtime enrichment, including when restoring an older cache. */
fun TmdbHome.featuredSlidesForDay(date: String): List<TmdbItem> {
    val recentStart = isoDateDaysBefore(date, 30)
    val recent =
        rows
            .firstOrNull { it.title == TmdbRowTitles.LATEST }
            ?.items
            .orEmpty()
            .take(24)
            .filter { item ->
                !item.backdropPath.isNullOrBlank() &&
                    item.releaseDate?.let { it >= recentStart && it <= date } == true
            }.distinctBy { it.mediaType to it.id }
            .rotateForDay(date, 3)
    val recentPicks = recent.take(3)
    val recentKeys = recentPicks.mapTo(mutableSetOf()) { it.mediaType to it.id }
    val popular =
        featured
            .filter { !it.backdropPath.isNullOrBlank() }
            .distinctBy { it.mediaType to it.id }
            .rotateForDay(date, 8 - recentPicks.size)
            .filterNot { (it.mediaType to it.id) in recentKeys }
    val popularPicks = popular.take(8 - recentPicks.size)
    // Spread new releases through the reel instead of putting all of them at the end.
    return buildList {
        repeat(maxOf(popularPicks.size, recentPicks.size)) { index ->
            popularPicks.getOrNull(index)?.let(::add)
            recentPicks.getOrNull(index)?.let(::add)
        }
        addAll(popular)
        addAll(recent)
    }.distinctBy { it.mediaType to it.id }.take(8)
}

/** Advance a whole batch each day; even a small pool must not always start at index zero. */
private fun <T> List<T>.rotateForDay(
    date: String,
    batchSize: Int,
): List<T> {
    if (size <= 1) return this
    val step = batchSize.coerceIn(1, size - 1)
    val day = isoEpochDay(date) ?: 0L
    val offset = ((((day % size) + size) % size) * step % size).toInt()
    return drop(offset) + take(offset)
}
