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
)

data class TmdbRow(val title: String, val items: List<TmdbItem>)

/** Aggregated TMDB content for the home tab. */
data class TmdbHome(
    val featured: List<TmdbItem> = emptyList(),
    val rows: List<TmdbRow> = emptyList(),
) {
    val isEmpty: Boolean get() = featured.isEmpty() && rows.isEmpty()
}
