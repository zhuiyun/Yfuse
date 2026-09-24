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
    /** Runtime used by the home carousel; populated from the TMDB detail endpoint. */
    val runtimeMinutes: Int? = null,
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
data class TmdbRow(
    val title: String,
    val items: List<TmdbItem>,
)

/**
 * The home tab's TMDB shelves. A shelf's title is also its identity when a partial refresh is
 * merged with the previous one, so code compares against these names, never against literals.
 */
object TmdbRowTitles {
    const val POPULAR = "热门"
    const val LATEST = "最新上线"
    const val NOW_PLAYING = "正在上映"
    const val UPCOMING = "即将上映"
}

/** Shelves whose cards lead with a date: what is about to open, or has only just opened. */
val TmdbRow.showsReleaseDate: Boolean
    get() = title == TmdbRowTitles.LATEST || title == TmdbRowTitles.UPCOMING

/** Aggregated TMDB content for the home tab. */
@Serializable
data class TmdbHome(
    val featured: List<TmdbItem> = emptyList(),
    val rows: List<TmdbRow> = emptyList(),
) {
    val isEmpty: Boolean get() = featured.isEmpty() && rows.isEmpty()
}
