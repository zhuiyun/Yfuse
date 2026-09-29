package com.yfuse.feature.detail

import com.yfuse.core.cast.CastManager
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.loadPlaybackCastItem

/** The player's own load, so a cast from the detail page is the one its 投屏 key makes. */
internal actual suspend fun castPlaybackQueue(
    castManager: CastManager,
    items: List<PlayerMediaItem>,
    deviceId: String,
    index: Int,
    positionMs: Long,
): Boolean = loadPlaybackCastItem(castManager, items, deviceId, index, positionMs)
