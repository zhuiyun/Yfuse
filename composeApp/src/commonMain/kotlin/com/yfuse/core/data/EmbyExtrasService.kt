package com.yfuse.core.data

import com.yfuse.core.data.dto.BaseItemDto
import com.yfuse.core.data.dto.EmbyMediaUrlDto
import com.yfuse.core.data.dto.EmbyPersonDto
import com.yfuse.core.data.dto.EmbyRemoteTrailersDto
import com.yfuse.core.data.dto.EmbyThemeMediaDto
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.PersonProfile
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ThemeSong
import com.yfuse.core.network.EmbyStream
import com.yfuse.core.network.mediaBrowserTokenQuery
import com.yfuse.core.network.normalizeBaseUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import java.time.LocalDate

/**
 * 预告片, 主题曲 and 演员 for Emby and Jellyfin.
 *
 * Every call is optional enrichment of a page that already stands without it, so failures come
 * back as a failed [Result] for [EmbyRepository] to log once and hide, rather than through
 * [embyApiCall]'s error-level report. The routes are the ones both servers still document:
 * Jellyfin 10.9–10.11 keep `/Users/{userId}/Items/{id}/LocalTrailers` beside the newer
 * `/Items/{id}/LocalTrailers`, and `/Items/{id}/ThemeSongs` is the same on both.
 */
internal class EmbyExtrasService(
    private val client: HttpClient,
) {
    suspend fun localTrailers(
        server: SavedServer,
        itemId: String,
    ): Result<List<MediaTrailer.Local>> =
        optionalCall {
            val items: List<BaseItemDto> =
                client
                    .get("${server.baseUrl}/Users/${embyPath(server.userId)}/Items/${embyPath(itemId)}/LocalTrailers") {
                        header("X-Emby-Token", server.accessToken)
                    }.body()
            embyLocalTrailers(server, items)
        }

    /** The trailer links the server scraped with its metadata; a request of its own, see the file note. */
    suspend fun remoteTrailers(
        server: SavedServer,
        itemId: String,
    ): Result<List<MediaTrailer.Remote>> =
        optionalCall {
            val dto: EmbyRemoteTrailersDto =
                client
                    .get("${server.baseUrl}/Users/${embyPath(server.userId)}/Items/${embyPath(itemId)}") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("Fields", "RemoteTrailers")
                    }.body()
            remoteTrailerLinks(dto.RemoteTrailers.orEmpty())
        }

    /** Inherited from the parent, so an episode or a season plays its show's theme. */
    suspend fun themeSongs(
        server: SavedServer,
        itemId: String,
    ): Result<List<ThemeSong>> =
        optionalCall {
            val dto: EmbyThemeMediaDto =
                client
                    .get("${server.baseUrl}/Items/${embyPath(itemId)}/ThemeSongs") {
                        header("X-Emby-Token", server.accessToken)
                        parameter("UserId", server.userId)
                        parameter("InheritFromParent", true)
                    }.body()
            embyThemeSongs(server, dto.Items)
        }

    suspend fun person(
        server: SavedServer,
        personId: String,
    ): Result<PersonProfile> =
        optionalCall {
            val dto: EmbyPersonDto =
                client
                    .get("${server.baseUrl}/Users/${embyPath(server.userId)}/Items/${embyPath(personId)}") {
                        header("X-Emby-Token", server.accessToken)
                        // All three are ItemFields on both servers; the dates come without asking.
                        parameter("Fields", "Overview,ProviderIds,ProductionLocations")
                    }.body()
            dto.toPersonProfile()
        }
}

/** Decoding happens off the UI thread, and cancellation stays cancellation. */
internal suspend fun <T> optionalCall(block: suspend () -> T): Result<T> =
    offUiThread { runCatchingCancellable { block() } }

internal fun embyLocalTrailers(
    server: SavedServer,
    items: List<BaseItemDto>,
): List<MediaTrailer.Local> {
    val files = items.filter { it.Id.isNotBlank() }.distinctBy(BaseItemDto::Id)
    return files.mapIndexed { index, item ->
        MediaTrailer.Local(
            serverId = server.id,
            itemId = item.Id,
            title = trailerTitle(item.Name, index, files.size),
            // The file itself, never a transcode: the player takes it as an outside address, which
            // is what keeps it from reporting progress to anyone (see TrailerPlayback).
            streamUrl = EmbyStream.directPlay(server.baseUrl, item.Id, server.accessToken, userId = server.userId),
            durationMs = item.RunTimeTicks?.div(TICKS_PER_MILLISECOND)?.takeIf { it > 0L },
        )
    }
}

internal fun embyThemeSongs(
    server: SavedServer,
    items: List<BaseItemDto>,
): List<ThemeSong> =
    items
        .filter { it.Id.isNotBlank() }
        .distinctBy(BaseItemDto::Id)
        .map { item ->
            ThemeSong(
                serverId = server.id,
                itemId = item.Id,
                title = item.Name?.trim()?.ifBlank { null } ?: "主题曲",
                streamUrl =
                    "${normalizeBaseUrl(server.baseUrl)}/Audio/${embyPath(item.Id)}/stream?static=true&" +
                        mediaBrowserTokenQuery(server.accessToken),
            )
        }

internal fun EmbyPersonDto.toPersonProfile(): PersonProfile =
    PersonProfile(
        id = Id,
        name = Name?.trim().orEmpty(),
        overview = Overview?.trim()?.ifBlank { null },
        birthDate = serverCalendarDay(PremiereDate),
        deathDate = serverCalendarDay(EndDate),
        birthPlace = ProductionLocations?.firstNotNullOfOrNull { it.trim().ifBlank { null } },
        primaryImageTag = ImageTags?.get("Primary"),
        providerIds = ProviderIds.orEmpty(),
    )

/**
 * Only addresses a browser or a video app can open: a Kodi-style `plugin://` entry, which some
 * scrapers write, would do nothing but fail when handed to the system.
 */
internal fun remoteTrailerLinks(entries: List<EmbyMediaUrlDto>): List<MediaTrailer.Remote> {
    val links =
        entries.mapNotNull { entry ->
            entry.Url
                ?.trim()
                ?.takeIf(::isWebAddress)
                ?.let { it to entry.Name }
        }
    val distinct = links.distinctBy { (url, _) -> url.lowercase() }
    return distinct.mapIndexed { index, (url, name) ->
        MediaTrailer.Remote(title = trailerTitle(name, index, distinct.size), url = url, site = trailerSite(url))
    }
}

/**
 * The name a server gave a trailer, unless it is only the word for one — `trailer`, as a file
 * name usually is — in which case it reads 预告片, numbered when there are several.
 */
internal fun trailerTitle(
    name: String?,
    index: Int = 0,
    count: Int = 1,
): String {
    val given = name?.trim().orEmpty()
    if (given.isNotEmpty() && given.lowercase() !in GENERIC_TRAILER_NAMES) return given
    return if (count > 1) "预告片 ${index + 1}" else "预告片"
}

/** What the card says a link opens: the service by name, or the bare host. */
internal fun trailerSite(url: String): String {
    val host =
        url
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore('?')
            .substringAfterLast('@')
            .substringBefore(':')
            .lowercase()
            .removePrefix("www.")
            .removePrefix("m.")
    return when {
        host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") -> "YouTube"
        host == "b23.tv" || host == "bilibili.com" || host.endsWith(".bilibili.com") -> "哔哩哔哩"
        host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
        else -> host.ifBlank { "外部网站" }
    }
}

private fun isWebAddress(value: String): Boolean {
    val scheme = value.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    return value.substringAfter("://").substringBefore('/').isNotBlank() && value.none(Char::isWhitespace)
}

/**
 * A server date as the calendar day it names, or null.
 *
 * Emby and Jellyfin keep a birthday as local midnight converted to UTC, so a server east of
 * Greenwich writes 27 June as `…-26T16:00:00Z`; the nearest whole day is the one that was meant.
 * DateTime's minimum, `0001-01-01`, is how "unknown" is written and stays unknown.
 */
internal fun serverCalendarDay(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val date = runCatching { LocalDate.parse(value.take(10)) }.getOrNull() ?: return null
    if (date.year < MIN_PLAUSIBLE_YEAR) return null
    val hour = value.drop(11).take(2).toIntOrNull()
    return if (hour != null && hour >= 12) date.plusDays(1).toString() else date.toString()
}

private val GENERIC_TRAILER_NAMES = setOf("trailer", "trailers", "预告", "预告片", "theatrical trailer")
private const val TICKS_PER_MILLISECOND = 10_000L
private const val MIN_PLAUSIBLE_YEAR = 1800
