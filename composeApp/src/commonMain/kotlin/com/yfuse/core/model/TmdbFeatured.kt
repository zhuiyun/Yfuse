package com.yfuse.core.model

import com.yfuse.core.util.isoDateDaysBefore
import com.yfuse.core.util.isoEpochDay

internal fun TmdbItem.carouselKey(): String = "$mediaType:$id"

/** Shared by the carousel and runtime enrichment, including when restoring an older cache. */
fun TmdbHome.featuredSlidesForDay(
    date: String,
    recentKeys: List<String> = emptyList(),
    keep: List<String> = emptyList(),
): List<TmdbItem> {
    val recentStart = isoDateDaysBefore(date, 30)
    val recent =
        rows
            .firstOrNull { it.title == TmdbRowTitles.LATEST }
            ?.items
            .orEmpty()
            .filter { item ->
                !item.backdropPath.isNullOrBlank() &&
                    item.releaseDate?.let { it >= recentStart && it <= date } == true
            }.distinctBy(TmdbItem::carouselKey)
            .rotateForDay(date, LATEST_CAROUSEL_SLOTS)
    val popular =
        (rows.firstOrNull { it.title == TmdbRowTitles.POPULAR }?.items.orEmpty() + featured)
            .filter { !it.backdropPath.isNullOrBlank() }
            .distinctBy(TmdbItem::carouselKey)
            .rotateForDay(date, HOME_CAROUSEL_SIZE - minOf(recent.size, LATEST_CAROUSEL_SLOTS))
    val kept = (recent + popular).retainCarouselItems(keep, TmdbItem::carouselKey)
    val openSlots = HOME_CAROUSEL_SIZE - kept.size
    if (openSlots == 0) return kept

    val shown = recentKeys.toSet()
    val recentCandidateKeys = recent.mapTo(mutableSetOf(), TmdbItem::carouselKey)
    val used = kept.mapTo(mutableSetOf(), TmdbItem::carouselKey)
    val remainingPopular = popular.filterNot { it.carouselKey() in used }
    val remainingRecent = recent.filterNot { it.carouselKey() in used }
    val keptRecent = used.count { it in recentCandidateKeys }
    val recentSlots = minOf(openSlots, (LATEST_CAROUSEL_SLOTS - keptRecent).coerceAtLeast(0))
    val recentPicks = recommendationBatch(remainingRecent, recentSlots, recentKeys, TmdbItem::carouselKey)
    recentPicks.mapTo(used, TmdbItem::carouselKey)
    val popularPicks =
        recommendationBatch(
            remainingPopular.filterNot { it.carouselKey() in used },
            openSlots - recentPicks.size,
            recentKeys,
            TmdbItem::carouselKey,
        )
    val mixed =
        buildList {
            repeat(maxOf(popularPicks.size, recentPicks.size)) { index ->
                popularPicks.getOrNull(index)?.let(::add)
                recentPicks.getOrNull(index)?.let(::add)
            }
        }
    val extras = recommendationBatch(remainingPopular + remainingRecent, openSlots, recentKeys, TmdbItem::carouselKey)
    // Keep the usual mix where possible, then exhaust unseen titles before repeating any.
    val picks = (mixed + extras).distinctBy(TmdbItem::carouselKey)
    return kept + picks.sortedBy { it.carouselKey() in shown }.take(openSlots)
}

private const val LATEST_CAROUSEL_SLOTS = 3

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
