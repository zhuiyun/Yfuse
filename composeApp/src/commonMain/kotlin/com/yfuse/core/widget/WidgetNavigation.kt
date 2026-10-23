package com.yfuse.core.widget

import com.yfuse.core.model.TmdbItem

/** A source's own item identifier is not a TMDB identity unless it explicitly declares one. */
fun WidgetMedia.tmdbDetailItemOrNull(): TmdbItem? {
    if (categoryParams.isNotEmpty()) return null
    val kind =
        when (mediaType.trim().lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "tv"
            else -> return null
        }
    val identity =
        tmdbId.trim().ifBlank {
            id.trim().takeIf { type.trim().equals("tmdb", ignoreCase = true) }.orEmpty()
        }
    if (identity.isEmpty() || identity.any { it !in '0'..'9' }) return null
    val numericId = identity.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return TmdbItem(
        id = numericId,
        title = title,
        overview = description.trim().takeIf(String::isNotEmpty),
        posterPath = poster.trim().takeIf(String::isNotEmpty),
        backdropPath = backdrop.trim().takeIf(String::isNotEmpty),
        year =
            year.trim().let { value ->
                value.take(4).takeIf { prefix ->
                    prefix.length == 4 &&
                        prefix.all { it in '0'..'9' } &&
                        (value.length == 4 || value.getOrNull(4) == '-')
                }
            },
        mediaType = kind,
        rating = rating.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 },
    )
}

/** Film and TV cards always use the app's detail page, regardless of source playback routes. */
fun WidgetMedia.standardTmdbDetailItemOrNull(): TmdbItem? = tmdbDetailItemOrNull()

/** Unknown identities open the same detail shell; source playback never depends on a TMDB match. */
fun WidgetMedia.widgetDetailSeed(): TmdbItem =
    tmdbDetailItemOrNull() ?: TmdbItem(
        id = 0,
        title = title,
        overview = description.takeIf(String::isNotBlank),
        posterPath = poster.takeIf(String::isNotBlank),
        backdropPath = backdrop.takeIf(String::isNotBlank),
        year = year.takeIf(String::isNotBlank),
        mediaType =
            if (isWidgetFilmOrSeries()) {
                if (mediaType.lowercase() == "movie") "movie" else "tv"
            } else {
                "video"
            },
        rating = rating.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 },
    )

fun WidgetMedia.isWidgetFilmOrSeries(): Boolean =
    mediaType.trim().lowercase() in setOf("movie", "tv", "series") &&
        type.trim().lowercase() !in setOf("live", "video", "url")

fun WidgetMedia.requiresWidgetDetailLookup(): Boolean =
    link.isNotBlank() &&
        !Regex(
            "https?://(?:www\\.)?themoviedb\\.org(?::\\d+)?/(?:movie|tv)/[0-9]+(?:[/?#].*)?",
            RegexOption.IGNORE_CASE,
        ).matches(link.trim())

/** A declared media-file link can play without a script's optional detail function. */
internal fun WidgetMedia.directWidgetLinkOrNull(): String? =
    link.takeIf { value ->
        (value.startsWith("http://", true) || value.startsWith("https://", true)) &&
            value.substringBefore('?').substringBefore('#').lowercase().let { path ->
                listOf(".m3u8", ".mpd", ".flv", ".mp4", ".mkv", ".webm", ".ts").any(path::endsWith)
            }
    }
