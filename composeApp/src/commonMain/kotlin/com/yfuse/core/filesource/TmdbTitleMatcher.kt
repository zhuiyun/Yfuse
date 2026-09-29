package com.yfuse.core.filesource

import com.yfuse.core.data.TmdbSearchResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

/** The TMDB title a 文件来源 file was matched to: what its 片库 card shows and plays under. */
@Serializable
data class FileSourceTitle(
    @SerialName("id") val tmdbId: Int,
    /** `movie` or `tv`. */
    @SerialName("k") val mediaType: String,
    @SerialName("n") val title: String,
    @SerialName("o") val originalTitle: String? = null,
    @SerialName("y") val year: Int? = null,
    @SerialName("p") val posterPath: String? = null,
    @SerialName("b") val backdropPath: String? = null,
    @SerialName("r") val rating: Double? = null,
) {
    /** `movie:603`: one title, however many files and shares hold it. */
    val key: String get() = "$mediaType:$tmdbId"

    val isSeries: Boolean get() = mediaType == TMDB_TV
}

/** What looking a name up came to. */
sealed interface TitleMatch {
    data class Found(
        val title: FileSourceTitle,
    ) : TitleMatch

    data object NotFound : TitleMatch

    /** TMDB could not be asked. The file is left for the next scan rather than called unknown. */
    data class Unavailable(
        val error: Throwable,
    ) : TitleMatch
}

/**
 * Finds the TMDB title a parsed name is, or says it cannot.
 *
 * Each candidate title is searched as the kind the name suggests — a film, or a show when it
 * carries an episode — and TMDB's hits are scored on how close their names come to it and whether
 * their years agree. A hit that only TMDB knows by another name (a romanised anime title, an
 * English file name for a Chinese film) is confirmed against its alternative titles before it is
 * taken. Chinese candidates go first: the Chinese page names every title, so they match exactly
 * where an English or romanised one only matches alike.
 *
 * Wrong is worse than unmatched here — a wrong match puts a file under someone else's poster in
 * the library — so nothing is taken on TMDB's ranking alone.
 */
class TmdbTitleMatcher(
    private val search: suspend (query: String, mediaType: String, year: Int?) -> Result<List<TmdbSearchResult>>,
    private val alternativeTitles: suspend (mediaType: String, id: Int) -> Result<List<String>>,
) {
    suspend fun match(parsed: ParsedMediaName): TitleMatch {
        val primary = if (parsed.kind == ParsedMediaKind.Episode) TMDB_TV else TMDB_MOVIE
        val queries = parsed.titles.orderedForSearch().take(MAX_QUERIES)
        if (queries.isEmpty()) return TitleMatch.NotFound
        for (query in queries) {
            val outcome = lookUp(parsed, query, primary, strict = false)
            if (outcome != TitleMatch.NotFound) return outcome
        }
        // A film filed like a show, or a special filed like a film: only a near-certain hit.
        val secondary = if (primary == TMDB_TV) TMDB_MOVIE else TMDB_TV
        return lookUp(parsed, queries.first(), secondary, strict = true)
    }

    private suspend fun lookUp(
        parsed: ParsedMediaName,
        query: String,
        mediaType: String,
        strict: Boolean,
    ): TitleMatch {
        // A film's year narrows the search; a show's first season is rarely the file's year.
        val year = parsed.year.takeIf { mediaType == TMDB_MOVIE }
        var results = search(query, mediaType, year).getOrElse { return TitleMatch.Unavailable(it) }
        if (results.isEmpty() && year != null) {
            results = search(query, mediaType, null).getOrElse { return TitleMatch.Unavailable(it) }
        }
        if (results.isEmpty()) return TitleMatch.NotFound
        parsed.tmdbId?.let { id -> results.firstOrNull { it.id == id }?.let { return TitleMatch.Found(it.toTitle()) } }
        val best =
            results
                .take(MAX_CANDIDATES)
                .mapIndexed { rank, result -> result to matchScore(parsed, query, result, rank) }
                .maxBy { it.second }
        if (best.second >= (if (strict) STRICT_SCORE else ACCEPT_SCORE)) return TitleMatch.Found(best.first.toTitle())
        if (strict) return TitleMatch.NotFound
        // TMDB found it by a name it does not show here; its first hit is confirmed or nothing is.
        val first = results.first()
        if (yearScore(parsed, first) < 0) return TitleMatch.NotFound
        val names = alternativeTitles(mediaType, first.id).getOrNull().orEmpty()
        return if (names.any { titleSimilarity(query, it) >= ALTERNATIVE_SIMILARITY }) {
            TitleMatch.Found(first.toTitle())
        } else {
            TitleMatch.NotFound
        }
    }
}

/**
 * How well [candidate] answers [query] for [parsed], out of 100: the name counts for up to 60, the
 * year from −40 to 30, and TMDB's own ranking for the rest.
 */
internal fun matchScore(
    parsed: ParsedMediaName,
    query: String,
    candidate: TmdbSearchResult,
    rank: Int,
): Int {
    val similarity =
        maxOf(
            titleSimilarity(query, candidate.title),
            candidate.originalTitle?.let { titleSimilarity(query, it) } ?: 0.0,
        )
    return (similarity * TITLE_WEIGHT).roundToInt() + yearScore(parsed, candidate) + RANK_BONUS.getOrElse(rank) { 0 }
}

/**
 * 1 for the same name, most of that when one name holds the other (`Star Wars` in `Star Wars
 * Episode IV A New Hope`), and otherwise the share of character pairs they have in common, which
 * is what tells 葬送的芙莉蓮 and 葬送的芙莉莲 apart from two unrelated titles.
 */
internal fun titleSimilarity(
    first: String,
    second: String,
): Double {
    val a = first.normalizedTitle()
    val b = second.normalizedTitle()
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    val shorter = if (a.length <= b.length) a else b
    val minimum = if (shorter.any { it.code >= CJK_START }) MIN_CJK_CONTAINED else MIN_LATIN_CONTAINED
    val contained = if (shorter.length >= minimum && (a in b || b in a)) CONTAINED_SIMILARITY else 0.0
    return maxOf(contained, bigramDice(a, b))
}

private fun bigramDice(
    a: String,
    b: String,
): Double {
    if (a.length < 2 || b.length < 2) return 0.0
    val left = a.zipWithNext().groupingBy { it }.eachCount()
    val right = b.zipWithNext().groupingBy { it }.eachCount()
    val shared = left.entries.sumOf { (pair, count) -> minOf(count, right[pair] ?: 0) }
    return 2.0 * shared / ((a.length - 1) + (b.length - 1))
}

/** Agreement between the file's year and the title's; negative when they cannot both be right. */
internal fun yearScore(
    parsed: ParsedMediaName,
    candidate: TmdbSearchResult,
): Int {
    val year = parsed.year ?: return 0
    val released = candidate.year ?: return 0
    // `Blade Runner 2049`, `请回答1988`: the number belonged to the title, not to the year.
    val digits = year.toString()
    if (digits in candidate.title || digits in candidate.originalTitle.orEmpty()) return 0
    return if (candidate.mediaType == TMDB_TV) {
        // A later season's file carries its own year, after the show's first.
        when {
            released == year -> YEAR_EXACT
            released < year -> YEAR_LATER_SEASON
            released == year + 1 -> YEAR_NEAR
            else -> YEAR_CONFLICT
        }
    } else {
        when (abs(released - year)) {
            0 -> YEAR_EXACT
            1 -> YEAR_NEAR
            else -> YEAR_CONFLICT
        }
    }
}

private fun List<String>.orderedForSearch(): List<String> =
    sortedByDescending { title -> title.any { it.code >= CJK_START && it.isLetter() } }

private fun TmdbSearchResult.toTitle(): FileSourceTitle =
    FileSourceTitle(
        tmdbId = id,
        mediaType = mediaType,
        title = title,
        originalTitle = originalTitle?.takeUnless { it == title },
        year = year,
        posterPath = posterPath,
        backdropPath = backdropPath,
        rating = rating,
    )

internal const val TMDB_MOVIE = "movie"
internal const val TMDB_TV = "tv"

private const val MAX_QUERIES = 3
private const val MAX_CANDIDATES = 8
private const val ACCEPT_SCORE = 50
private const val STRICT_SCORE = 70
private const val TITLE_WEIGHT = 60
private const val CONTAINED_SIMILARITY = 0.75
private const val ALTERNATIVE_SIMILARITY = 0.8
private const val MIN_CJK_CONTAINED = 2
private const val MIN_LATIN_CONTAINED = 4
private const val CJK_START = 0x3040
private const val YEAR_EXACT = 30
private const val YEAR_NEAR = 15
private const val YEAR_LATER_SEASON = 5
private const val YEAR_CONFLICT = -40
private val RANK_BONUS = listOf(10, 5)
