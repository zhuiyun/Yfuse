package com.yfuse.feature.player

import kotlin.math.min

/**
 * The chapter starts among a rail's markers, in order: where the bar is divided and what the
 * preview card calls each part. The skip boundaries (片头, 片尾) are markers but not chapters.
 */
internal fun List<PlaybackProgressMarker>.chapterMarkers(): List<PlaybackProgressMarker> =
    filter { it.chapter && !it.label.isNullOrBlank() }.sortedBy(PlaybackProgressMarker::positionMs)

/** Which of [chapters] [positionMs] falls in — the last one to start at or before it — or -1 before the first. */
internal fun chapterIndexAt(
    chapters: List<PlaybackProgressMarker>,
    positionMs: Long,
): Int {
    var found = -1
    for (index in chapters.indices) {
        if (chapters[index].positionMs > positionMs) break
        found = index
    }
    return found
}

/** The name of the chapter [positionMs] falls in; null before the first chapter or without any. */
internal fun chapterNameAt(
    chapters: List<PlaybackProgressMarker>,
    positionMs: Long,
): String? = chapters.getOrNull(chapterIndexAt(chapters, positionMs))?.label

/**
 * Where the rail is cut between chapters, as fractions of it. A chapter starting at the very
 * beginning or the very end cuts nothing, and neither does an unknown duration.
 */
internal fun chapterBoundaryFractions(
    chapters: List<PlaybackProgressMarker>,
    durationMs: Long,
): List<Float> {
    if (durationMs <= 0L) return emptyList()
    return chapters
        .map { (it.positionMs.toDouble() / durationMs).toFloat() }
        .filter { it > 0f && it < 1f }
        .distinct()
}

/**
 * How far, as a fraction of a [widthPx]-wide rail, the magnet reaches for a marker: [radiusPx]
 * (14 dp), never more than 4% of the rail.
 *
 * Chapters can sit much closer together than that — a disc-style file cut every two or three
 * minutes puts them a few millimetres apart on a phone — and a 14 dp pull around each would leave
 * nothing between them that the thumb could rest on. So the reach also stays under a third of the
 * narrowest chapter: wherever chapters are sparse the magnet is the one it always was.
 */
internal fun seekMagnetFraction(
    radiusPx: Float,
    widthPx: Float,
    chapterFractions: List<Float>,
): Float {
    val base = (radiusPx / widthPx.coerceAtLeast(1f)).coerceIn(0f, MAX_MAGNET_FRACTION)
    val starts = chapterFractions.sorted()
    if (starts.size < 2) return base
    var narrowest = 1f
    for (index in 1 until starts.size) narrowest = min(narrowest, starts[index] - starts[index - 1])
    return min(base, narrowest / 3f)
}

private const val MAX_MAGNET_FRACTION = 0.04f
