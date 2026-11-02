package com.yfuse.core.model

import com.yfuse.core.network.TheOtherDbQuery
import com.yfuse.core.network.TheOtherDbVersion
import kotlin.math.abs
import kotlin.math.roundToLong

/** Never combine an intro from one cut with credits from another. Unknown duration waits. */
fun matchOnlineSkipSegments(
    versions: List<TheOtherDbVersion>,
    query: TheOtherDbQuery,
    durationMs: Long,
): List<PlaybackSegment> {
    if (durationMs <= 0L) return emptyList()
    val candidates =
        versions.filter {
            it.runtime.isFinite() &&
                it.runtime > 0.0 &&
                it.runtime <= 36_000.0 &&
                abs(it.runtime * 1_000.0 - durationMs) <= 2_000.0 &&
                if (query.isTv) {
                    it.videoType == "tv" && it.season?.number == query.season && it.episode?.number == query.episode
                } else {
                    it.videoType == "movie"
                }
        }
    if (candidates.isEmpty()) return emptyList()
    // Even similarly long cuts may have different openings. Accept only unanimous boundaries.
    val alternatives = candidates.map { it.validSegments(durationMs) }.distinct()
    return alternatives.singleOrNull().orEmpty()
}

private fun TheOtherDbVersion.validSegments(durationMs: Long): List<PlaybackSegment> {
    val runtimeMs = (runtime * 1_000.0).roundToLong()
    val segments =
        markers
            .mapNotNull { marker ->
                val end = marker.end ?: return@mapNotNull null
                if (!marker.start.isFinite() ||
                    !end.isFinite() ||
                    marker.start < 0.0 ||
                    end > runtime
                ) {
                    return@mapNotNull null
                }
                val startMs = (marker.start * 1_000.0).roundToLong()
                val endMs = (end * 1_000.0).roundToLong()
                val length = endMs - startMs
                if (length !in 5_000L..600_000L) return@mapNotNull null
                when (marker.type) {
                    "intro" ->
                        if (endMs < durationMs / 3) {
                            PlaybackSegment(PlaybackSegmentType.Intro, startMs, endMs)
                        } else {
                            null
                        }
                    // Only a terminal credits range can advance to the next item. Chapters, previews,
                    // null ends and credits before a post-credit scene must not skip unseen content.
                    "credits" ->
                        if (runtimeMs - endMs <= 2_000L && startMs >= runtimeMs * 4 / 5) {
                            val leadMs = runtimeMs - startMs
                            PlaybackSegment(PlaybackSegmentType.Credits, durationMs - leadMs, null)
                        } else {
                            null
                        }
                    else -> null
                }
            }.distinct()
    return PlaybackSegmentType.entries.mapNotNull { type -> segments.filter { it.type == type }.singleOrNull() }
}

/** The server knows the local file; community markers fill only its missing segment types. */
fun mergeOnlineSkipSegments(
    server: List<PlaybackSegment>,
    online: List<PlaybackSegment>,
): List<PlaybackSegment> = server + online.filter { candidate -> server.none { it.type == candidate.type } }
