package com.yfuse.core.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
enum class RatingSource(
    val label: String,
    val mark: String,
    val maximum: Int = 10,
) {
    Tmdb("TMDB", "TMDB"),
    Imdb("IMDb", "IMDb"),
    Douban("豆瓣", "豆"),
    RottenTomatoes("烂番茄影评", "RT", 100),
    RottenTomatoesAudience("烂番茄观众", "RT", 100),
    Metacritic("Metacritic", "m", 100),
    Library("媒体库评分", "★"),
    LibraryCritics("媒体库影评", "评", 100),
}

@Serializable
data class MediaRating(
    val source: RatingSource,
    val value: Double,
) {
    val valid: Boolean
        get() =
            value.isFinite() &&
                value >= 0.0 &&
                value <= source.maximum &&
                (
                    value > 0.0 ||
                        source == RatingSource.RottenTomatoes ||
                        source == RatingSource.RottenTomatoesAudience ||
                        source == RatingSource.Metacritic
                )
    val display: String
        get() =
            if (source ==
                RatingSource.Metacritic
            ) {
                value.roundToInt().toString()
            } else if (source.maximum ==
                100
            ) {
                "${value.roundToInt()}%"
            } else {
                val tenths = (value * 10).roundToInt()
                "${tenths / 10}.${tenths % 10}"
            }
}

fun List<MediaRating>.validRatings(): List<MediaRating> = filter { it.valid }.distinctBy { it.source }

fun MediaDetail.displayRatings(): List<MediaRating> =
    ratings.validRatings().ifEmpty {
        communityRating
            ?.let {
                listOf(
                    MediaRating(RatingSource.Library, it),
                ).validRatings()
            }.orEmpty()
    }

/** Plex image identifiers declare provenance. External IDs alone do not prove a score's source. */
fun plexRating(
    value: Double?,
    image: String?,
    type: String?,
): MediaRating? {
    if (value == null || !value.isFinite() || value < 0 || value > 10) return null
    val audience = type.equals("audience", ignoreCase = true)
    val source =
        when (image?.substringBefore(":")?.lowercase()) {
            "imdb" -> RatingSource.Imdb
            "themoviedb", "tmdb" -> RatingSource.Tmdb
            "douban" -> RatingSource.Douban
            "metacritic" -> RatingSource.Metacritic
            "rottentomatoes" -> if (audience) RatingSource.RottenTomatoesAudience else RatingSource.RottenTomatoes
            else -> if (audience) RatingSource.Library else RatingSource.LibraryCritics
        }
    return MediaRating(source, value * source.maximum / 10).takeIf { it.valid }
}
