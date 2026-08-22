package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscChapter

/**
 * Adds real disc chapter positions to the progress-marker API while retaining the existing
 * skip-marker implementation. Chapters without a known start position are ignored.
 */
internal fun playbackProgressMarkers(
    skip: SkipSegmentState,
    durationMs: Long,
    chapters: List<PlaybackDiscChapter>,
): List<PlaybackProgressMarker> {
    if (durationMs <= 0L) return emptyList()

    val markers = playbackProgressMarkers(skip, durationMs).toMutableList()
    chapters.forEach { chapter ->
        chapter.startMs?.let { startMs ->
            markers +=
                PlaybackProgressMarker(
                    positionMs = startMs.coerceIn(0L, durationMs),
                    label = chapter.title?.takeIf { it.isNotBlank() },
                )
        }
    }

    return markers
        .distinctBy { it.positionMs to it.label }
        .sortedBy(PlaybackProgressMarker::positionMs)
}
