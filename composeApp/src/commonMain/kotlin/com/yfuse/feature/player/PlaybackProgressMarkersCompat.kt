package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscChapter

/**
 * Compatibility overload for the rollback build.
 *
 * The chapter-marker test already targets the newer three-argument API while the reverted
 * PlayerChrome still exposes the older skip-only helper. Keep the production rollback intact and
 * layer chapter markers on top of that helper for this signing build.
 */
internal fun playbackProgressMarkers(
    skip: SkipSegmentState,
    durationMs: Long,
    chapters: List<PlaybackDiscChapter>,
): List<PlaybackProgressMarker> {
    if (durationMs <= 0L) return emptyList()

    val markers = playbackProgressMarkers(skip, durationMs).toMutableList()
    chapters.forEach { chapter ->
        val position = chapter.startMs?.coerceIn(0L, durationMs) ?: return@forEach
        markers +=
            PlaybackProgressMarker(
                positionMs = position,
                label = chapter.title?.takeIf { it.isNotBlank() },
            )
    }

    return markers
        .distinctBy { it.positionMs to it.label }
        .sortedBy(PlaybackProgressMarker::positionMs)
}
