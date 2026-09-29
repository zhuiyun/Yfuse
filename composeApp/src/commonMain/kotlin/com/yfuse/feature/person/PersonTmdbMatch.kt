package com.yfuse.feature.person

import com.yfuse.core.model.MediaItem
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbPersonDetail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** How 演员页 found the person on TMDB, or why it shows no TMDB half. */
internal sealed interface TmdbPersonMatch {
    val detail: TmdbPersonDetail?
        get() = null

    /** The server had already linked the person to this TMDB record. */
    data class ById(
        override val detail: TmdbPersonDetail,
    ) : TmdbPersonMatch

    /** Same name, and credited on [overlap] titles this library holds. */
    data class ByName(
        override val detail: TmdbPersonDetail,
        val overlap: Int,
    ) : TmdbPersonMatch

    /** Nothing shown; [reason] goes to the log, never to the screen. */
    data class None(
        val reason: String,
    ) : TmdbPersonMatch
}

/**
 * Finds the person on TMDB — or nobody.
 *
 * A TMDB id the server stored is the person, and nothing is guessed. Without one a name is only a
 * name: two actors share one more often than it seems, and a wrong filmography under someone's
 * face is worse than none. So a candidate counts only when one of its names is exactly this one
 * *and* TMDB credits it on at least one title this library holds; when two candidates both
 * qualify, neither is shown.
 *
 * [details] fetches one TMDB record; the few candidates it is asked for are read together.
 */
internal suspend fun resolveTmdbPerson(
    name: String,
    tmdbId: Int?,
    libraryKeys: Set<String>,
    search: suspend (String) -> List<TmdbPerson>,
    details: suspend (Int) -> TmdbPersonDetail?,
): TmdbPersonMatch {
    if (tmdbId != null) {
        return details(tmdbId)?.let(TmdbPersonMatch::ById) ?: TmdbPersonMatch.None("record_unavailable")
    }
    val wanted = personNameKey(name)
    if (wanted.isEmpty()) return TmdbPersonMatch.None("no_name")
    // Nothing to corroborate a name with, so it is not trusted on its own.
    if (libraryKeys.isEmpty()) return TmdbPersonMatch.None("no_library_titles")
    val candidates = search(name.trim()).distinctBy(TmdbPerson::id).take(MAX_NAME_CANDIDATES)
    if (candidates.isEmpty()) return TmdbPersonMatch.None("no_candidates")
    val records = coroutineScope { candidates.map { async { details(it.id) } }.awaitAll() }.filterNotNull()
    val corroborated =
        records
            .filter { record -> record.names.any { personNameKey(it) == wanted } }
            .map { record -> record to record.credits.count { it.tmdbKey() in libraryKeys } }
            .filter { (_, overlap) -> overlap > 0 }
    return when (corroborated.size) {
        0 -> TmdbPersonMatch.None("uncorroborated")
        1 -> corroborated.single().let { (record, overlap) -> TmdbPersonMatch.ByName(record, overlap) }
        else -> TmdbPersonMatch.None("ambiguous")
    }
}

/**
 * TMDB 其他作品: what TMDB credits them on that this library does not hold, best known first.
 * Titles without any artwork would be blank cards, and are left out.
 */
internal fun otherWorks(
    detail: TmdbPersonDetail,
    libraryKeys: Set<String>,
    limit: Int = MAX_OTHER_WORKS,
): List<TmdbItem> =
    detail.credits
        .asSequence()
        .filter { it.tmdbKey() !in libraryKeys }
        .filter { it.posterPath != null || it.backdropPath != null }
        .sortedWith(compareByDescending<TmdbItem> { it.voteCount }.thenByDescending { it.popularity })
        .take(limit)
        .toList()

/** The library's titles as TMDB keys them — `movie:603`, `tv:1399` — for the ones that carry an id. */
internal fun libraryTmdbKeys(works: List<MediaItem>): Set<String> = works.mapNotNull { it.tmdbKey() }.toSet()

internal fun MediaItem.tmdbKey(): String? {
    val tmdbId =
        providerIds.entries
            .firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.toIntOrNull()
            ?: return null
    val mediaType =
        when (type) {
            "Movie" -> "movie"
            "Series" -> "tv"
            else -> return null
        }
    return "$mediaType:$tmdbId"
}

internal fun TmdbItem.tmdbKey(): String = "$mediaType:$id"

/**
 * One name however it was typed: case, surrounding and repeated spaces, full-width Latin letters
 * and the several dots that join a transliterated name (约瑟夫·高登-莱维特 is written with four of
 * them in the wild) do not make two names different. Nothing else is folded — 梁朝伟 and 梁朝偉
 * stay two spellings, and TMDB's aliases are what carry the other one.
 */
internal fun personNameKey(name: String): String =
    name
        .map { char ->
            when (char) {
                in FULL_WIDTH_ASCII -> char - FULL_WIDTH_OFFSET
                '　' -> ' '
                '・', '•', '‧', '∙', '･' -> '·'
                else -> char
            }
        }.joinToString("")
        .lowercase()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .joinToString(" ")

private val FULL_WIDTH_ASCII = '！'..'～'
private const val FULL_WIDTH_OFFSET = 0xFEE0

/** Enough for the right person to be among them; each costs one TMDB request. */
private const val MAX_NAME_CANDIDATES = 3

private const val MAX_OTHER_WORKS = 40
