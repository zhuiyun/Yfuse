package com.yfuse.core.model

/** Sprite-sheet layout advertised by Jellyfin's Trickplay item field. */
data class TrickplayInfo(
    val width: Int,
    val height: Int,
    val tileColumns: Int,
    val tileRows: Int,
    val intervalMs: Long,
    val thumbnailCount: Int,
)

