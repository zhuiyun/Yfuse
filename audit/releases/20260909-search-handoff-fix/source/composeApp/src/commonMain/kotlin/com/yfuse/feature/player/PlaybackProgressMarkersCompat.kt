package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscChapter

/**
 * Adds meaningful disc chapter positions to the progress-marker API while retaining the existing
 * skip-marker implementation. Untitled chapter boundaries are intentionally omitted: without a
 * label they read as decorative ruler ticks instead of useful navigation landmarks.
 */
internal fun playbackProgressMarkers(
    skip: SkipSegmentState,
    durationMs: Long,
    chapters: List<PlaybackDiscChapter>,
): List<PlaybackProgressMarker> {
    if (durationMs <= 0L) return emptyList()

    val markers = playbackProgressMarkers(skip, durationMs).toMutableList()
    chapters.forEach { chapter ->
        val title = chapter.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        chapter.startMs?.let { startMs ->
            markers +=
                PlaybackProgressMarker(
                    positionMs = startMs.coerceIn(0L, durationMs),
                    label = title,
                )
        }
    }

    return markers
        .distinctBy { it.positionMs to it.label }
        .sortedBy(PlaybackProgressMarker::positionMs)
}
