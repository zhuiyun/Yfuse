package com.yfuse.core.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

// Trailers, theme music and people. Kept apart from BaseItemDto on purpose: these fields are asked
// for by requests of their own, so an Emby or Jellyfin version that rejects one of them costs that
// one part of the page and never the detail request every screen is built from.

/** Emby and Jellyfin's `MediaUrl` — one entry of an item's RemoteTrailers. */
@Serializable
data class EmbyMediaUrlDto(
    val Url: String? = null,
    val Name: String? = null,
)

/** `/Users/{userId}/Items/{id}?Fields=RemoteTrailers`, reduced to the one field it was asked for. */
@Serializable
data class EmbyRemoteTrailersDto(
    val RemoteTrailers: List<EmbyMediaUrlDto>? = null,
)

/**
 * `/Items/{id}/ThemeSongs` — Emby and Jellyfin's ThemeMediaResult. Its OwnerId is an integer on
 * Emby and a GUID on Jellyfin, and nothing here needs it, so it is not declared.
 */
@Serializable
data class EmbyThemeMediaDto(
    val Items: List<BaseItemDto> = emptyList(),
)

/** A Person item read through `/Users/{userId}/Items/{id}`. */
@Serializable
data class EmbyPersonDto(
    val Id: String,
    val Name: String? = null,
    val Overview: String? = null,
    /** Birth date on a person. */
    val PremiereDate: String? = null,
    /** Death date on a person. */
    val EndDate: String? = null,
    /** Birthplace on a person. */
    val ProductionLocations: List<String>? = null,
    val ProviderIds: Map<String, String>? = null,
    val ImageTags: Map<String, String>? = null,
)

/** `/library/metadata/{ratingKey}/extras`. */
@Serializable
data class PlexExtrasResponseDto(
    val MediaContainer: PlexExtrasContainerDto = PlexExtrasContainerDto(),
)

@Serializable
data class PlexExtrasContainerDto(
    val Metadata: List<PlexExtraDto> = emptyList(),
)

/**
 * One extra of a Plex title: a clip whose [subtype] says what it is (`trailer`, `behindTheScenes`,
 * …) and whose [extraType] is 1 for a trailer. Either may be missing depending on the version.
 */
@Serializable
data class PlexExtraDto(
    val ratingKey: String? = null,
    val key: String? = null,
    val type: String? = null,
    val subtype: String? = null,
    /** A number on current servers; a string on some older ones. */
    val extraType: JsonPrimitive? = null,
    val title: String? = null,
    val duration: Long? = null,
    val Media: List<PlexExtraMediaDto> = emptyList(),
)

@Serializable
data class PlexExtraMediaDto(
    /** Set on media that has to be resolved through a channel before it can be read. */
    val indirect: JsonPrimitive? = null,
    val Part: List<PlexExtraPartDto> = emptyList(),
)

@Serializable
data class PlexExtraPartDto(
    val key: String? = null,
)

/** `/library/metadata/{ratingKey}`, reduced to the theme paths; an episode only has its show's. */
@Serializable
data class PlexThemeResponseDto(
    val MediaContainer: PlexThemeContainerDto = PlexThemeContainerDto(),
)

@Serializable
data class PlexThemeContainerDto(
    val Metadata: List<PlexThemeOwnerDto> = emptyList(),
)

@Serializable
data class PlexThemeOwnerDto(
    val ratingKey: String? = null,
    val theme: String? = null,
    val parentTheme: String? = null,
    val grandparentTheme: String? = null,
)
