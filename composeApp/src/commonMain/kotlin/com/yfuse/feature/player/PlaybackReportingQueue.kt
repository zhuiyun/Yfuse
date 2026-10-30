package com.yfuse.feature.player

/** Indexes belong to the queue installed in the engine, while metadata may arrive later. */
internal fun playbackReportingQueue(
    engineItems: List<PlayerMediaItem>,
    latestItems: List<PlayerMediaItem>,
): List<PlayerMediaItem> =
    engineItems.map { installed ->
        latestItems.firstOrNull { latest ->
            latest.serverId == installed.serverId &&
                latest.id == installed.id &&
                latest.playSessionId == installed.playSessionId
        } ?: installed
    }
