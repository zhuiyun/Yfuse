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

private val canonicalTmdbDetailLink = Regex("([A-Za-z]+)://([^/]+)/(movie|tv)/([1-9][0-9]*)/?")

/** Standard details cannot carry a widget's playback URLs, child routes, or source-detail loader. */
fun WidgetMedia.standardTmdbDetailItemOrNull(): TmdbItem? {
    val item = tmdbDetailItemOrNull() ?: return null
    if (videoUrl.isNotBlank() || children.isNotEmpty()) return null
    val detailLink = link.trim()
    if (detailLink.isEmpty()) return item
    val match = canonicalTmdbDetailLink.matchEntire(detailLink) ?: return null
    val defaultPort =
        when (match.groupValues[1].lowercase()) {
            "http" -> "80"
            "https" -> "443"
            else -> return null
        }
    val authority = match.groupValues[2].lowercase()
    val officialAuthority =
        authority == "themoviedb.org" ||
            authority == "www.themoviedb.org" ||
            authority == "themoviedb.org:$defaultPort" ||
            authority == "www.themoviedb.org:$defaultPort"
    if (!officialAuthority) return null
    return item.takeIf {
        match.groupValues[3] == it.mediaType && match.groupValues[4].toIntOrNull() == it.id
    }
}
