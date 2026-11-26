package com.yfuse.core.model

import kotlinx.serialization.Serializable

internal const val HOME_CAROUSEL_SIZE = 8
private const val CAROUSEL_HISTORY_SIZE = 4 * HOME_CAROUSEL_SIZE

@Serializable
data class CarouselBatch<T>(
    val date: String,
    val items: List<T>,
    val recentKeys: List<String>,
)

internal fun <T> CarouselBatch<T>?.selectCarouselBatch(
    date: String,
    rotate: Boolean,
    key: (T) -> String,
    select: (keep: List<String>, recent: List<String>) -> List<T>,
): CarouselBatch<T>? {
    val previousKeys = this?.items.orEmpty().map(key)
    val recent = this?.recentKeys.orEmpty()
    val replace = rotate || this?.date != date
    val picked = select(if (replace) emptyList() else previousKeys, recent)
    if (picked.isEmpty() && this == null) return null

    // Even a pool too small to change membership can give the lead card a turn.
    val items =
        if (replace && picked.size > 1 && picked.map(key) == previousKeys) {
            picked.drop(1) + picked.first()
        } else {
            picked
        }
    val keys = items.map(key)
    val history =
        if (keys == previousKeys && !replace) {
            recent
        } else {
            recordRecommendationExposure(recent, keys).takeLast(CAROUSEL_HISTORY_SIZE)
        }
    return CarouselBatch(date, items, history)
}

internal fun <T> List<T>.retainCarouselItems(
    keep: List<String>,
    key: (T) -> String,
): List<T> {
    if (keep.isEmpty()) return emptyList()
    val byKey = associateBy(key)
    return keep.distinct().mapNotNull(byKey::get).take(HOME_CAROUSEL_SIZE)
}
