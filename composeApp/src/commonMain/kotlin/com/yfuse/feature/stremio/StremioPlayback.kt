package com.yfuse.feature.stremio

import com.yfuse.core.stremio.StremioMeta
import com.yfuse.core.stremio.StremioStream
import com.yfuse.core.stremio.StremioSubtitle
import com.yfuse.core.stremio.StremioVideo
import com.yfuse.core.stremio.isStremioHttpUrl
import com.yfuse.feature.player.PlayerExternalSubtitle
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.externalPlaybackItem
import java.util.UUID

internal fun stremioPlaybackItem(
    meta: StremioMeta,
    video: StremioVideo,
    stream: StremioStream,
    subtitles: List<StremioSubtitle>,
): PlayerMediaItem {
    require(stream.unsupportedReason == null) { stream.unsupportedReason.orEmpty() }
    val identity = UUID.nameUUIDFromBytes("${meta.type}:${video.id}".encodeToByteArray())
    return externalPlaybackItem(
        requireNotNull(stream.url),
        if (meta.type ==
            "series"
        ) {
            "${meta.name} · ${video.label}"
        } else {
            meta.name
        },
    ).copy(
        id = "external-stremio-$identity",
        watchKey = "external-stremio-$identity",
        matchKeys = listOf("external-stremio-$identity"),
        posterUrl = meta.poster?.takeIf(::isStremioHttpUrl),
        stillUrl = video.thumbnail?.takeIf(::isStremioHttpUrl),
        seasonNumber = video.season,
        episodeNumber = video.episode,
        mediaType = if (meta.type == "series") "Episode" else "Movie",
        externalSubtitles =
            (stream.subtitles + subtitles).filter { isStremioHttpUrl(it.url) }.distinctBy { it.url }.take(100).map {
                PlayerExternalSubtitle(
                    uri = it.url,
                    language = it.lang,
                    codec =
                        it.url.substringBefore('?').substringAfterLast('.', "").takeIf { ext ->
                            ext in
                                setOf("srt", "vtt", "ass", "ssa")
                        },
                )
            },
    )
}
