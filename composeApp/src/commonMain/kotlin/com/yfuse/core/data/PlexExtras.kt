package com.yfuse.core.data

import com.yfuse.core.data.dto.PlexExtraDto
import com.yfuse.core.data.dto.PlexThemeOwnerDto
import com.yfuse.core.model.MediaTrailer
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ThemeSong
import kotlinx.serialization.json.JsonPrimitive

// Plex's side of 预告片 and 主题曲, as pure mapping from what the server answered. The requests
// themselves live in PlexMediaServerAdapter, which owns the Plex headers.

/**
 * The trailers among a title's extras — `subtype="trailer"`, or `extraType` 1 on servers that only
 * send the number — that point at a file on this server. Behind-the-scenes clips, interviews and
 * the rest are extras too and are left out. Media marked `indirect` has to be resolved through a
 * channel first, which a plain address cannot do, so it is left out as well.
 */
internal fun plexTrailers(
    server: SavedServer,
    extras: List<PlexExtraDto>,
): List<MediaTrailer.Local> {
    val playable =
        extras
            .filter { it.isTrailer() }
            .mapNotNull { extra ->
                val part = extra.playablePartKey() ?: return@mapNotNull null
                val id =
                    extra.ratingKey?.trim()?.ifBlank { null } ?: extra.key?.substringAfterLast('/')?.ifBlank { null }
                id?.let { Triple(extra, it, part) }
            }.distinctBy { (_, id, _) -> id }
    return playable.mapIndexed { index, (extra, id, part) ->
        MediaTrailer.Local(
            serverId = server.id,
            itemId = id,
            title = trailerTitle(extra.title, index, playable.size),
            streamUrl = plexAuthenticatedUrl(server.baseUrl, part, server.accessToken),
            durationMs = extra.duration?.takeIf { it > 0L },
        )
    }
}

/** The item's own theme, else its season's or show's — which is all an episode carries. */
internal fun plexThemeSong(
    server: SavedServer,
    itemId: String,
    owner: PlexThemeOwnerDto,
): ThemeSong? {
    val path =
        listOfNotNull(owner.theme, owner.parentTheme, owner.grandparentTheme)
            .map(String::trim)
            .firstOrNull { it.startsWith('/') && !it.startsWith("//") }
            ?: return null
    return ThemeSong(
        serverId = server.id,
        itemId = owner.ratingKey?.trim()?.ifBlank { null } ?: itemId,
        // A Plex theme has no name of its own; the file is only ever `theme`.
        title = "主题曲",
        streamUrl = plexAuthenticatedUrl(server.baseUrl, path, server.accessToken),
    )
}

private fun PlexExtraDto.isTrailer(): Boolean =
    subtype?.trim().equals("trailer", ignoreCase = true) || extraType?.content?.trim() == PLEX_TRAILER_EXTRA_TYPE

/** A part key on this server; [plexAuthenticatedUrl] refuses anything else anyway. */
private fun PlexExtraDto.playablePartKey(): String? =
    Media
        .asSequence()
        .filterNot { it.indirect.isSet() }
        .flatMap { it.Part.asSequence() }
        .mapNotNull { it.key?.trim() }
        .firstOrNull { it.startsWith('/') && !it.startsWith("//") }

private fun JsonPrimitive?.isSet(): Boolean = this?.content?.trim()?.lowercase() in setOf("1", "true")

private const val PLEX_TRAILER_EXTRA_TYPE = "1"
