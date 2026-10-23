package com.yfuse.core.widget

import com.yfuse.core.data.TmdbSearchResult
import com.yfuse.core.model.TmdbItem

internal fun WidgetMedia.detailSearchYear(): Int? {
    val value = year.trim()
    return value
        .take(4)
        .takeIf {
            it.length == 4 &&
                it.all { digit -> digit in '0'..'9' } &&
                (value.length == 4 || value.getOrNull(4) == '-')
        }?.toIntOrNull()
        ?.takeIf { it > 0 }
}

/** Only an unambiguous title/year match may navigate without asking the person to choose. */
internal fun WidgetMedia.exactDetailMatch(results: List<TmdbSearchResult>): TmdbSearchResult? {
    val query = title.normalizedTitle()
    if (query.isEmpty() || categoryParams.isNotEmpty()) return null
    val kind =
        when (mediaType.trim().lowercase()) {
            "movie" -> "movie"
            "tv", "series" -> "tv"
            else -> return null
        }
    val expectedYear = detailSearchYear()
    return results
        .filter {
            it.id > 0 &&
                it.mediaType == kind &&
                (expectedYear == null || it.year == expectedYear) &&
                (it.title.normalizedTitle() == query || it.originalTitle?.normalizedTitle() == query)
        }.distinctBy { it.id }
        .singleOrNull()
}

internal fun TmdbSearchResult.widgetDetailItem() =
    TmdbItem(
        id = id,
        title = title,
        overview = overview,
        posterPath = posterPath,
        backdropPath = backdropPath,
        year = year?.toString(),
        mediaType = mediaType,
        rating = rating,
        voteCount = voteCount,
        popularity = popularity,
    )

private fun String.normalizedTitle() = trim().lowercase().replace(Regex("\\s+"), " ")
