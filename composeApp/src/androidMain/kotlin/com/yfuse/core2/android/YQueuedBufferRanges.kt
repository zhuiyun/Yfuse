package com.yfuse.core2.android

import com.yfuse.core2.api.YBufferedRange
import java.util.SortedMap

/** Sweep retained packet endpoints. A timestamp gap must not become a cached interval. */
internal fun queuedTimestampRanges(
    starts: SortedMap<Long, Int>,
    ends: SortedMap<Long, Int>,
): List<YBufferedRange> {
    val first = starts.entries.iterator()
    val last = ends.entries.iterator()
    var start = if (first.hasNext()) first.next() else null
    var end = if (last.hasNext()) last.next() else null
    var retained = 0
    var openUs = 0L
    val ranges = mutableListOf<YBufferedRange>()
    while (start != null || end != null) {
        val timeUs = minOf(start?.key ?: Long.MAX_VALUE, end?.key ?: Long.MAX_VALUE)
        if (retained == 0) openUs = timeUs
        val starting = start
        val ending = end
        if (starting != null && starting.key == timeUs) {
            retained += starting.value
            start = if (first.hasNext()) first.next() else null
        }
        if (ending != null && ending.key == timeUs) {
            retained -= ending.value
            end = if (last.hasNext()) last.next() else null
        }
        if (retained == 0) {
            // Round inward; sub-millisecond gaps cannot expand a retained packet's coverage.
            val fromMs = openUs.coerceAtLeast(0L) / 1_000L + if (openUs > 0 && openUs % 1_000L != 0L) 1 else 0
            val toMs = timeUs / 1_000L
            if (toMs > fromMs) ranges += YBufferedRange(fromMs, toMs)
            if (ranges.size == 256) break
        }
    }
    return ranges
}

/** Every selected audio/video track must retain each advertised interval. */
internal fun intersectQueuedRanges(tracks: List<List<YBufferedRange>>): List<YBufferedRange> =
    tracks
        .reduceOrNull { left, right ->
            val result = mutableListOf<YBufferedRange>()
            var a = 0
            var b = 0
            while (a < left.size && b < right.size) {
                val from = maxOf(left[a].startMs, right[b].startMs)
                val to = minOf(left[a].endMs, right[b].endMs)
                if (to > from) result += YBufferedRange(from, to)
                if (left[a].endMs < right[b].endMs) a++ else b++
            }
            result
        }.orEmpty()
