package com.yfuse.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/**
 * One title as a picture a friend can open anywhere: poster, name, year and score, plus the
 * public TMDB and 豆瓣 pages.
 *
 * Nothing here may point at the viewer's own server — no address, no token, no item id. The
 * [posterUrl] is only read on this device to paint the card, and leaves it as pixels; the text
 * that travels with the picture is built from [title], [year], [rating] and the two public ids.
 */
@Immutable
data class PosterShareCard(
    val title: String,
    val year: Int? = null,
    /** Out of ten, as the detail page shows it. */
    val rating: Double? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null,
    /**
     * TMDB's own `movie` / `tv`, or the server's `Movie` / `Series` / `Season` / `Episode`;
     * anything else counts as a film.
     */
    val mediaType: String? = null,
    val doubanId: String? = null,
    /**
     * For a season or an episode, its series' TMDB id. [tmdbId] is then the item's own, which no
     * TMDB page is addressed by, so without this the card carries no TMDB link at all.
     */
    val seriesTmdbId: String? = null,
    /** Where TMDB files a season or an episode under its series; the series page without them. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
)

/**
 * 分享海报卡片: renders the card and hands it to the system share sheet.
 *
 * Screens take one from [rememberPosterCardSharer]. The call returns at once; the poster is
 * fetched and the card painted off the main thread, and the share sheet opens when it is ready.
 * A card that cannot be painted is still shared, as its text and links.
 */
interface PosterCardSharer {
    fun sharePosterCard(card: PosterShareCard)

    fun sharePosterCard(
        title: String,
        year: Int?,
        rating: Double?,
        posterUrl: String?,
        tmdbId: String?,
        mediaType: String?,
        doubanId: String? = null,
    ) = sharePosterCard(PosterShareCard(title, year, rating, posterUrl, tmdbId, mediaType, doubanId))
}

@Composable
expect fun rememberPosterCardSharer(): PosterCardSharer

/**
 * TMDB's page for the title; null without a numeric id, so nothing else can be passed off as one.
 *
 * A season or an episode is linked under its series — `/tv/<series>/season/<n>/episode/<m>` —
 * and not at all without [seriesTmdbId]. Its own id is not a series id: `/tv/<episode id>` opened
 * some unrelated show.
 */
fun tmdbTitleUrl(
    tmdbId: String?,
    mediaType: String?,
    seriesTmdbId: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String? {
    val kind = mediaType?.trim()?.lowercase()
    if (kind == "season" || kind == "episode") {
        val series = seriesTmdbId?.trim()?.takeIf(::isPublicId) ?: return null
        // Season 0 is where TMDB keeps the specials; episodes count from 1.
        val season = seasonNumber?.takeIf { it in 0..MAX_TMDB_NUMBER }
        val episode = episodeNumber?.takeIf { kind == "episode" && it in 1..MAX_TMDB_NUMBER }
        return buildString {
            append("https://www.themoviedb.org/tv/$series")
            if (season != null) {
                append("/season/$season")
                if (episode != null) append("/episode/$episode")
            }
        }
    }
    val id = tmdbId?.trim()?.takeIf(::isPublicId) ?: return null
    return when (kind) {
        "tv", "series", "show" -> "https://www.themoviedb.org/tv/$id"
        else -> "https://www.themoviedb.org/movie/$id"
    }
}

private const val MAX_TMDB_NUMBER = 9_999

/** 豆瓣's page for the title; null without a numeric subject id. */
fun doubanTitleUrl(doubanId: String?): String? =
    doubanId?.trim()?.takeIf(::isPublicId)?.let { "https://movie.douban.com/subject/$it/" }

/** The line under the title on the card: 「2024 · ★ 8.1」, either half alone, or null. */
fun posterCardMeta(
    year: Int?,
    rating: Double?,
): String? =
    listOfNotNull(
        year?.takeIf { it in 1_800..9_999 }?.toString(),
        posterCardRating(rating)?.let { "★ $it" },
    ).joinToString(" · ").ifEmpty { null }

/** One decimal out of ten, or null for a missing or meaningless score. */
fun posterCardRating(rating: Double?): String? {
    val value = rating?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    return ((value.coerceAtMost(10.0) * 10.0).roundToInt() / 10.0).toString()
}

/**
 * The text that travels with the picture: 「《片名》 2024 · ★ 8.1」 and the public links, one a line.
 * Built only from the card's public fields, never from [PosterShareCard.posterUrl].
 */
fun posterCardCaption(card: PosterShareCard): String =
    buildList {
        add(
            listOfNotNull(
                "《${card.title.trim()}》",
                posterCardMeta(card.year, card.rating),
            ).joinToString(" "),
        )
        tmdbTitleUrl(
            tmdbId = card.tmdbId,
            mediaType = card.mediaType,
            seriesTmdbId = card.seriesTmdbId,
            seasonNumber = card.seasonNumber,
            episodeNumber = card.episodeNumber,
        )?.let { add("TMDB：$it") }
        doubanTitleUrl(card.doubanId)?.let { add("豆瓣：$it") }
    }.joinToString("\n")

private fun isPublicId(value: String): Boolean = value.length in 1..12 && value.all { it in '0'..'9' }
