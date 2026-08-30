package com.yfuse.core.model

/** Sprite-sheet layout advertised by Jellyfin's Trickplay item field. */
data class TrickplayInfo(
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val intervalMs: Long,
    val thumbnailCount: Int,
    /** Provider-owned frame URL. Null keeps the Emby/Jellyfin tile endpoint. */
    val urlPattern: String? = null,
    /** Multiplies the storyboard index before replacing `{index}` (Plex BIF uses milliseconds). */
    val urlIndexMultiplier: Long = 1L,
)
