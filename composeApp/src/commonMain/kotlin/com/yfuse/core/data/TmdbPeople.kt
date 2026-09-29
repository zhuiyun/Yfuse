package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbPersonDetail
import com.yfuse.core.network.TMDB_BASE
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// 演员页's TMDB half: `/person/{id}` with its combined credits and translations in one request,
// and `/search/person` for a server that never linked the person to TMDB. Both go through the
// app's one TMDB client, with the token it was built with.

@Serializable
internal data class TmdbPersonDetailDto(
    val id: Int,
    val name: String? = null,
    @SerialName("also_known_as") val alsoKnownAs: List<String> = emptyList(),
    val biography: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    @SerialName("place_of_birth") val placeOfBirth: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    @SerialName("known_for_department") val knownForDepartment: String? = null,
    @SerialName("combined_credits") val combinedCredits: TmdbCombinedCreditsDto = TmdbCombinedCreditsDto(),
    val translations: TmdbPersonTranslationsDto = TmdbPersonTranslationsDto(),
)

@Serializable
internal data class TmdbCombinedCreditsDto(
    val cast: List<TmdbCreditDto> = emptyList(),
    val crew: List<TmdbCreditDto> = emptyList(),
)

/** One entry of combined_credits: a film carries title/release_date, a show name/first_air_date. */
@Serializable
internal data class TmdbCreditDto(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
    val character: String? = null,
    val department: String? = null,
    val adult: Boolean = false,
)

@Serializable
internal data class TmdbPersonTranslationsDto(
    val translations: List<TmdbPersonTranslationDto> = emptyList(),
)

@Serializable
internal data class TmdbPersonTranslationDto(
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("iso_3166_1") val region: String? = null,
    val data: TmdbPersonTranslationDataDto = TmdbPersonTranslationDataDto(),
)

@Serializable
internal data class TmdbPersonTranslationDataDto(
    val biography: String? = null,
    val name: String? = null,
)

@Serializable
internal data class TmdbPersonSearchDto(
    val results: List<TmdbPersonHitDto> = emptyList(),
)

@Serializable
internal data class TmdbPersonHitDto(
    val id: Int,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
    val adult: Boolean = false,
)

internal class TmdbPeopleService(
    private val client: HttpClient,
) {
    suspend fun person(
        personId: Int,
        language: String,
    ): Result<TmdbPersonDetail> =
        runCatchingCancellable {
            client
                .get("$TMDB_BASE/person/$personId") {
                    parameter("language", language)
                    parameter("append_to_response", "combined_credits,translations")
                }.body<TmdbPersonDetailDto>()
                .toPersonDetail(language)
        }.onFailure {
            AppLog.warning(
                category = "tmdb",
                event = "person_unavailable",
                message = "TMDB person record is unavailable; 其他作品 stays hidden",
                throwable = it,
            )
        }

    /** People TMDB files under [query], in its own relevance order; adult-only entries left out. */
    suspend fun search(
        query: String,
        language: String,
    ): Result<List<TmdbPerson>> =
        runCatchingCancellable {
            client
                .get("$TMDB_BASE/search/person") {
                    parameter("query", query)
                    parameter("language", language)
                    parameter("include_adult", false)
                }.body<TmdbPersonSearchDto>()
                .results
                .filterNot(TmdbPersonHitDto::adult)
                .mapNotNull { hit ->
                    listOf(hit.name, hit.originalName).firstNotNullOfOrNull { it?.trim()?.ifBlank { null } }?.let {
                        TmdbPerson(id = hit.id, name = it, role = null, profilePath = hit.profilePath)
                    }
                }
        }.onFailure {
            AppLog.warning(
                category = "tmdb",
                event = "person_search_unavailable",
                message = "TMDB person search is unavailable; 其他作品 stays hidden",
                throwable = it,
            )
        }
}

internal fun TmdbPersonDetailDto.toPersonDetail(language: String): TmdbPersonDetail {
    val primary =
        name?.trim()?.ifBlank { null } ?: alsoKnownAs.firstNotNullOfOrNull { it.trim().ifBlank { null } }.orEmpty()
    val translatedNames =
        translations.translations.mapNotNull {
            it.data.name
                ?.trim()
                ?.ifBlank { null }
        }
    return TmdbPersonDetail(
        person = TmdbPerson(id = id, name = primary, role = null, profilePath = profilePath),
        names =
            (
                listOf(
                    primary,
                ) + translatedNames + alsoKnownAs.map(String::trim)
            ).filter(String::isNotEmpty).distinct(),
        knownForDepartment = knownForDepartment?.trim()?.ifBlank { null },
        biography = personBiography(language),
        birthday = birthday?.trim()?.ifBlank { null },
        deathday = deathday?.trim()?.ifBlank { null },
        placeOfBirth = placeOfBirth?.trim()?.ifBlank { null },
        credits = personCredits(),
    )
}

/**
 * The biography in the page's language, else in another Chinese one, else in English: TMDB answers a
 * language it has no text for with an empty string rather than falling back on its own.
 */
private fun TmdbPersonDetailDto.personBiography(language: String): String? {
    biography?.trim()?.ifBlank { null }?.let { return it }
    val requested = language.substringBefore('-').lowercase()

    fun translated(accept: (TmdbPersonTranslationDto) -> Boolean): String? =
        translations.translations.firstNotNullOfOrNull { entry ->
            entry.data.biography
                ?.trim()
                ?.ifBlank { null }
                ?.takeIf { accept(entry) }
        }
    return translated { it.language.equals(requested, ignoreCase = true) }
        ?: translated { it.language.equals("en", ignoreCase = true) }
}

/**
 * What they are credited on, as cards: every acting role, and for someone TMDB knows for another
 * department — a director, a writer — the work in that department too. Talk, news and reality
 * shows and appearances as themselves are an actor's diary rather than their work, and go.
 */
private fun TmdbPersonDetailDto.personCredits(): List<TmdbItem> {
    val department = knownForDepartment?.trim()?.takeUnless { it.equals("Acting", ignoreCase = true) }
    val roles =
        combinedCredits.cast.filterNot { it.character.isSelfAppearance() } +
            combinedCredits.crew.filter { department != null && it.department.equals(department, ignoreCase = true) }
    return roles
        .asSequence()
        .filterNot { it.adult }
        .filter { it.mediaType == "movie" || it.mediaType == "tv" }
        .filter { credit -> credit.genreIds.none(DIARY_GENRE_IDS::contains) }
        .mapNotNull { it.toItem() }
        .distinctBy { "${it.mediaType}:${it.id}" }
        .toList()
}

private fun TmdbCreditDto.toItem(): TmdbItem? {
    val type = mediaType ?: return null
    val label = (title ?: name)?.trim()?.ifBlank { null } ?: return null
    val date = (releaseDate ?: firstAirDate)?.trim()?.ifBlank { null }
    return TmdbItem(
        id = id,
        title = label,
        overview = overview?.ifBlank { null },
        posterPath = posterPath,
        backdropPath = backdropPath,
        year = date?.take(4),
        mediaType = type,
        rating = voteAverage?.takeIf { it > 0.0 },
        releaseDate = date,
        voteCount = voteCount ?: 0,
        popularity = popularity ?: 0.0,
        genreIds = genreIds,
        originalLanguage = originalLanguage,
    )
}

private fun String?.isSelfAppearance(): Boolean {
    val role = this?.trim()?.lowercase() ?: return false
    return role.startsWith("self") || role in SELF_ROLES || "archive footage" in role
}

private val SELF_ROLES = setOf("himself", "herself", "themselves", "本人", "自己")

/** TMDB's News, Reality and Talk genres. */
private val DIARY_GENRE_IDS = setOf(10763, 10764, 10767)
