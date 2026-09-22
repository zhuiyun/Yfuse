package com.yfuse.core.model

/** Provider-neutral seek-preview metadata (Jellyfin sheets, Emby frames, or Plex indexes). */
data class TrickplayInfo(
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val intervalMs: Long,
    val thumbnailCount: Int,
    /** Provider-owned frame URL. Null keeps Jellyfin's regular tile endpoint. */
    val urlPattern: String? = null,
    /** Multiplies the storyboard index before replacing `{index}` (Plex BIF uses milliseconds). */
    val urlIndexMultiplier: Long = 1L,
    /** Exact provider timestamps. Emby exposes these instead of a regular sprite-sheet grid. */
    val frames: List<TrickplayTimelineFrame> = emptyList(),
)

data class TrickplayTimelineFrame(
    val positionMs: Long,
    val url: String,
)
