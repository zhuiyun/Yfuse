package com.yfuse.feature.player

import com.yfuse.core.model.PlaybackChapter
import com.yfuse.core.playback.PlaybackDiscChapter

/**
 * Adds meaningful disc chapter positions to the progress-marker API while retaining the existing
 * skip-marker implementation. Untitled chapter boundaries are intentionally omitted: without a
 * label they read as decorative ruler ticks instead of useful navigation landmarks.
 *
 * Chapter markers are flagged as such: the rail is divided at them rather than ticked, and the
 * preview card names the one the playhead is in.
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
                    chapter = true,
                )
        }
    }

    return markers
        .distinctBy { it.positionMs to it.label }
        .sortedBy(PlaybackProgressMarker::positionMs)
}

/** An ordinary file's named chapters, in the shape the chapter-aware markers take. */
internal fun List<PlaybackChapter>.asProgressChapters(): List<PlaybackDiscChapter> =
    mapIndexed { index, chapter ->
        PlaybackDiscChapter(index = index, title = chapter.name, startMs = chapter.startMs)
    }
