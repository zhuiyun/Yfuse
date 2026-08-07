package com.yfuse.core.model

import kotlinx.serialization.Serializable

/** A recommendation entry from TMDB. Serializable so it can ride in nav configs. */
@Serializable
data class TmdbItem(
    val id: Int,
    val title: String,
    val overview: String?,
    val posterPath: String?,
    val backdropPath: String?,
    val year: String?,
    val mediaType: String,
    val rating: Double?,
    val releaseDate: String? = null,
    val voteCount: Int = 0,
    val popularity: Double = 0.0,
    val genreIds: List<Int> = emptyList(),
    val originalLanguage: String? = null,
)

data class TmdbPerson(
    val id: Int,
    val name: String,
    val role: String?,
    val profilePath: String?,
)

/** Full TMDB metadata used even when the title is absent from Emby. */
data class TmdbDetail(
    val item: TmdbItem,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val numberOfSeasons: Int? = null,
    val status: String? = null,
    val tagline: String? = null,
    val cast: List<TmdbPerson> = emptyList(),
)

@Serializable
data class TmdbRow(val title: String, val items: List<TmdbItem>)

/** Aggregated TMDB content for the home tab. */
@Serializable
data class TmdbHome(
    val featured: List<TmdbItem> = emptyList(),
    val rows: List<TmdbRow> = emptyList(),
) {
    val isEmpty: Boolean get() = featured.isEmpty() && rows.isEmpty()
}
