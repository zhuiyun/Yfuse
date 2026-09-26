package com.yfuse.feature.library

import com.yfuse.core.model.LibrarySort
import com.yfuse.core.model.MediaItem
import kotlin.math.floor
import kotlin.math.roundToInt

// ---------------------------------------------------------------- 快速滚动索引 (5.6)

/** What a poster files under in the index: [short] on the strip, [long] in the bubble beside the finger. */
internal data class GridIndexLabel(
    val short: String,
    val long: String,
)

/** One stop on the index: its label and the first poster that carries it. */
internal data class GridIndexSection(
    val label: GridIndexLabel,
    val firstIndex: Int,
)

/**
 * The index's label for [item] under [sort]: the pinyin (or Latin) initial of the title for 名称,
 * the year for 年份, the month it was added for 最近添加, the whole score for 评分. Null where the
 * item has nothing to file under — it joins whatever section it falls in.
 */
internal fun gridIndexLabel(
    sort: LibrarySort,
    item: MediaItem,
): GridIndexLabel? =
    when (sort) {
        LibrarySort.Name -> nameIndexLetter(item.title).let { GridIndexLabel(it, it) }
        LibrarySort.Year -> yearIndexLabel(item.year)
        LibrarySort.RecentlyAdded -> monthIndexLabel(item.dateCreated)
        LibrarySort.Rating -> ratingIndexLabel(item.communityRating)
    }

internal fun yearIndexLabel(year: Int?): GridIndexLabel? =
    year?.takeIf { it in 1..9999 }?.let { GridIndexLabel(it.toString(), "${it}年") }

/** `2026-09-26` files under `26.9`, told out as 2026年9月. */
internal fun monthIndexLabel(date: String?): GridIndexLabel? {
    val text = date?.trim() ?: return null
    if (text.length < 7 || text[4] != '-') return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(5, 7).toIntOrNull()?.takeIf { it in 1..12 } ?: return null
    return GridIndexLabel("${(year % 100).toString().padStart(2, '0')}.$month", "${year}年${month}月")
}

/** A score files under its whole number: 8.6 under 8. */
internal fun ratingIndexLabel(rating: Double?): GridIndexLabel? {
    val value = rating?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val whole = floor(value.coerceAtMost(10.0)).toInt()
    return GridIndexLabel(whole.toString(), "${whole}分")
}

/** A–Z for a title that starts with a Latin letter, # for anything else. */
internal fun asciiIndexLetter(title: String): String {
    val first = title.firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (first in 'a'..'z' || first in 'A'..'Z') first.uppercaseChar().toString() else "#"
}

/** A–Z for a title — its pinyin initial when it starts in Chinese — or # for anything else. */
internal expect fun nameIndexLetter(title: String): String

/**
 * The index for posters labelled [labels], in grid order: one stop per label, at its first poster.
 * A label that comes back further down — a server's order is not always the index's — keeps its
 * first stop, so the strip reads top to bottom in the order the grid does.
 */
internal fun gridIndexSections(labels: List<GridIndexLabel?>): List<GridIndexSection> {
    val seen = HashSet<String>()
    val sections = ArrayList<GridIndexSection>()
    labels.forEachIndexed { index, label ->
        if (label != null && seen.add(label.short)) sections += GridIndexSection(label, index)
    }
    return sections
}

/** The stop a finger at [fraction] of the strip's height points to: 0 at the top, 1 at the bottom. */
internal fun indexSectionAt(
    fraction: Float,
    count: Int,
): Int {
    if (count <= 0) return -1
    val clamped = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    return (clamped * count).toInt().coerceAtMost(count - 1)
}

/**
 * Which of [count] stops the strip can spell out in [slots] rows: all of them when they fit,
 * otherwise evenly spaced ones, always the first and the last. The finger still reaches every stop.
 */
internal fun indexStripStops(
    count: Int,
    slots: Int,
): List<Int> {
    if (count <= 0 || slots <= 0) return emptyList()
    if (count <= slots) return List(count) { it }
    if (slots == 1) return listOf(0)
    return List(slots) { slot -> (slot * (count - 1) / (slots - 1).toFloat()).roundToInt() }.distinct()
}
