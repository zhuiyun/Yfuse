package com.yfuse.core.data

import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaDetail
import com.yfuse.core.model.MediaItem

internal fun Episode.toPlaybackLookupItem(): MediaItem =
    MediaItem(
        id = id,
        title = name,
        subtitle = null,
        type = "Episode",
        posterItemId = id,
        posterTag = primaryTag,
        backdropItemId = null,
        backdropTag = null,
        playedPercentage = playedPercentage,
        resumePositionTicks = resumePositionTicks,
        overview = overview,
        providerIds = providerIds,
        played = played,
    )

internal fun MediaDetail.toPlaybackLookupItem(): MediaItem =
    MediaItem(
        id = id,
        title = title,
        subtitle = year?.toString(),
        type = type,
        posterItemId = posterItemId,
        posterTag = posterTag,
        backdropItemId = backdropItemId,
        backdropTag = backdropTag,
        playedPercentage = null,
        resumePositionTicks = resumePositionTicks,
        year = year,
        overview = overview,
        communityRating = communityRating,
        providerIds = providerIds,
        played = played,
    )
