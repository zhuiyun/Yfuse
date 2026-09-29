package com.yfuse.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One TMDB search hit with both of its names: [title] in the language asked for, for display, and
 * [originalTitle] as released, for matching — `The Matrix` is found by the English file name even
 * when the Chinese page calls it 黑客帝国.
 */
data class TmdbSearchResult(
    val id: Int,
    /** `movie` or `tv`. */
    val mediaType: String,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val rating: Double?,
    val voteCount: Int,
    val popularity: Double,
)

@Serializable
internal data class TmdbSearchPageDto(
    val results: List<TmdbSearchResultDto> = emptyList(),
)

@Serializable
internal data class TmdbSearchResultDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    val overview: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
)

/** `/movie/{id}/alternative_titles` names its list `titles`; the `/tv/` one names it `results`. */
@Serializable
internal data class TmdbAlternativeTitlesDto(
    val titles: List<TmdbAlternativeTitleDto> = emptyList(),
    val results: List<TmdbAlternativeTitleDto> = emptyList(),
)

@Serializable
internal data class TmdbAlternativeTitleDto(
    val title: String? = null,
)

internal fun TmdbSearchResultDto.toSearchResult(mediaType: String): TmdbSearchResult? {
    val name = (title ?: name)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return TmdbSearchResult(
        id = id,
        mediaType = mediaType,
        title = name,
        originalTitle = (originalTitle ?: originalName)?.trim()?.takeIf(String::isNotEmpty),
        year = (releaseDate ?: firstAirDate)?.take(YEAR_DIGITS)?.toIntOrNull(),
        posterPath = posterPath,
        backdropPath = backdropPath,
        overview = overview?.trim()?.takeIf(String::isNotEmpty),
        rating = voteAverage?.takeIf { it > 0.0 },
        voteCount = voteCount,
        popularity = popularity,
    )
}

internal fun TmdbAlternativeTitlesDto.names(): List<String> =
    (titles + results).mapNotNull { it.title?.trim()?.takeIf(String::isNotEmpty) }.distinct()

private const val YEAR_DIGITS = 4
