package com.yfuse.core.data

import com.yfuse.core.model.HOME_CAROUSEL_SIZE
import com.yfuse.core.model.HomeContent
import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.recommendationBatch
import com.yfuse.core.model.retainCarouselItems

internal fun HomeContent.libraryFeaturedSlides(
    keep: List<String> = emptyList(),
    recentKeys: List<String> = emptyList(),
): List<MediaItem> {
    val previews =
        rows
            .filterNot { it.libraryId == FAVORITES_COLLECTION_ID || it.libraryId == WATCH_LATER_COLLECTION_ID }
            .map { row -> row.items.filter { !it.backdropTag.isNullOrBlank() } }
    val latest =
        buildList {
            repeat(previews.maxOfOrNull { it.size } ?: 0) { index ->
                previews.forEach { row -> row.getOrNull(index)?.let(::add) }
            }
        }.distinctBy(MediaItem::id)
    val kept =
        (resume + featured + latest)
            .filter { !it.backdropTag.isNullOrBlank() }
            .retainCarouselItems(keep, MediaItem::id)
    if (kept.size == HOME_CAROUSEL_SIZE) return kept
    val used = kept.mapTo(mutableSetOf(), MediaItem::id)
    val primary =
        recommendationBatch(
            latest.filterNot { it.id in used },
            HOME_CAROUSEL_SIZE - kept.size,
            recentKeys,
            MediaItem::id,
        )
    primary.mapTo(used, MediaItem::id)
    val extras =
        recommendationBatch(
            (resume + featured).filter { it.id !in used && !it.backdropTag.isNullOrBlank() },
            HOME_CAROUSEL_SIZE - kept.size - primary.size,
            recentKeys,
            MediaItem::id,
        )
    return kept + primary + extras
}
