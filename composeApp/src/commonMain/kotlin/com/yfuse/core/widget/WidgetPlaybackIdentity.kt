package com.yfuse.core.widget

import com.yfuse.core.model.TmdbItem
import com.yfuse.core.sync.playback.PlaybackStateRecord
import com.yfuse.feature.player.PlayerCustomHeaders
import com.yfuse.feature.player.PlayerMediaItem
import com.yfuse.feature.player.externalPlaybackItem
import java.util.UUID

/** A title/episode identity, independent of expiring URLs or the resource selected to play it. */
data class WidgetPlaybackIdentity(
    val mediaKey: String,
    val seriesKey: String?,
    val title: String,
    val tmdbId: Int?,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    val aliases: List<String> = listOf(mediaKey),
) {
    val itemId: String get() = "external-widget-${UUID.nameUUIDFromBytes(mediaKey.encodeToByteArray())}"
}

data class WidgetPlaybackRequest(
    val identity: WidgetPlaybackIdentity,
    val positionMs: Long = 0L,
    val source: WidgetSourceContext? = null,
)

fun WidgetSourceContext.playbackIdentity(
    item: TmdbItem = media.widgetDetailSeed(),
    season: Int? = media.season,
    episode: Int? = media.episode,
): WidgetPlaybackIdentity {
    val kind = if (item.mediaType in setOf("tv", "series")) "tv" else "movie"
    val tmdb = item.id.takeIf { it > 0 }
    val fallbackRoot =
        run {
            val sourceId = media.id.takeUnless { it.startsWith("http", true) }.orEmpty()
            val name = media.title.trim().lowercase()
            val identity = "$widgetId:$kind:$sourceId:$name:${media.year}"
            "widget:${UUID.nameUUIDFromBytes(identity.encodeToByteArray())}"
        }
    val root = tmdb?.let { "tmdb:$it" } ?: fallbackRoot
    val coordinate = kind == "tv" && season != null && episode != null
    return WidgetPlaybackIdentity(
        mediaKey = if (coordinate) "$root/s${season}e$episode" else root,
        seriesKey = root.takeIf { kind == "tv" },
        title = item.title,
        tmdbId = tmdb,
        year = item.year?.take(4)?.toIntOrNull(),
        season = season.takeIf { coordinate },
        episode = episode.takeIf { coordinate },
        aliases = listOf(root, fallbackRoot).distinct().map { if (coordinate) "$it/s${season}e$episode" else it },
    )
}

/** Watched/reset records stay authoritative: an old positive position must not revive them. */
internal fun PlaybackStateRecord?.widgetResumePositionMs(): Long =
    this?.takeUnless { it.played }?.positionMs?.coerceAtLeast(0L) ?: 0L

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

/** History retains catalog identity, never playback URLs, request headers or widget secrets. */
internal fun WidgetSourceContext.historySource(): WidgetSourceContext =
    copy(
        media =
            media.copy(
                id =
                    media.id
                        .takeUnless { it == media.videoUrl }
                        .orEmpty()
                        .historyCatalogLink(),
                link =
                    media.link
                        .takeUnless {
                            it == media.videoUrl || media.directWidgetLinkOrNull() != null
                        }.orEmpty()
                        .historyCatalogLink(),
                poster = media.poster.historyCatalogLink(),
                backdrop = media.backdrop.historyCatalogLink(),
                videoUrl = "",
                headers = emptyMap(),
                children = emptyList(),
            ),
        params = kotlinx.serialization.json.JsonObject(emptyMap()),
    )

private fun String.historyCatalogLink(): String {
    if (!startsWith("https://", true) && !startsWith("http://", true)) return this
    return runCatching {
        val uri = java.net.URI(this)
        val query =
            uri.rawQuery
                ?.split('&')
                ?.filterNot {
                    java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase().let { key ->
                        listOf(
                            "token",
                            "secret",
                            "sign",
                            "credential",
                            "password",
                            "auth",
                            "api_key",
                            "api-key",
                            "apikey",
                        ).any(key::contains)
                    }
                }?.joinToString("&")
                ?.takeIf(String::isNotEmpty)
        val host = uri.rawAuthority.substringAfterLast('@')
        "${uri.scheme}://$host${uri.rawPath.orEmpty()}" + query?.let { "?$it" }.orEmpty()
    }.getOrDefault("")
}
