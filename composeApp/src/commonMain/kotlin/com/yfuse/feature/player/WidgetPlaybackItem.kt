package com.yfuse.feature.player

import com.yfuse.core.playback.PlayerCustomHeaders
import com.yfuse.core.widget.WidgetMedia
import com.yfuse.core.widget.WidgetPlaybackRequest
import com.yfuse.core.widget.historySource

/** Turns a widget's media entry into the queue item the player runs, keeping its catalog identity. */
internal fun widgetPlaybackItem(
    media: WidgetMedia,
    request: WidgetPlaybackRequest?,
): PlayerMediaItem {
    val identity = request?.identity
    val item = externalPlaybackItem(media.videoUrl, identity?.title ?: media.title)
    return item.copy(
        id = identity?.itemId ?: item.id,
        watchKey = identity?.mediaKey ?: item.watchKey,
        matchKeys = identity?.aliases ?: item.matchKeys,
        posterUrl = media.poster,
        stillUrl = media.backdrop,
        seasonNumber = identity?.season ?: media.season,
        episodeNumber = identity?.episode ?: media.episode,
        seriesId = identity?.seriesKey,
        seriesKey = identity?.seriesKey,
        seriesName = identity?.title?.takeIf { identity.seriesKey != null },
        mediaType = if (identity?.seriesKey != null) "Episode" else "Movie",
        providerIds = identity?.tmdbId?.let { mapOf("Tmdb" to it.toString()) }.orEmpty(),
        year = identity?.year,
        widgetSource = request?.source?.historySource(),
        customHeaders =
            PlayerCustomHeaders(
                media.headers.filterKeys { !it.equals("X-Forward-Skip-Redirect-Probe", true) },
            ),
    )
}
