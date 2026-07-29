package com.yfuse.core.data

import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.TMDB_BASE
import com.yfuse.core.util.currentIsoDate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbListDto(val results: List<TmdbItemDto> = emptyList())

@Serializable
internal data class TmdbItemDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int = 0,
    val popularity: Double = 0.0,
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    @SerialName("original_language") val originalLanguage: String? = null,
)

@Serializable
internal data class TmdbGenreDto(val name: String)

@Serializable
internal data class TmdbCastDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
internal data class TmdbCreditsDto(val cast: List<TmdbCastDto> = emptyList())

@Serializable
internal data class TmdbDetailDto(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    val genres: List<TmdbGenreDto> = emptyList(),
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val status: String? = null,
    val tagline: String? = null,
    val credits: TmdbCreditsDto = TmdbCreditsDto(),
)

private fun TmdbItemDto.toItem(fallbackType: String): TmdbItem = TmdbItem(
    id = id,
    title = title ?: name ?: "",
    overview = overview?.ifBlank { null },
    posterPath = posterPath,
    backdropPath = backdropPath,
    year = (releaseDate ?: firstAirDate)?.take(4)?.ifBlank { null },
    releaseDate = (releaseDate ?: firstAirDate)?.ifBlank { null },
    mediaType = mediaType ?: fallbackType,
    rating = voteAverage?.takeIf { it > 0.0 },
    voteCount = voteCount,
    popularity = popularity,
    genreIds = genreIds,
    originalLanguage = originalLanguage,
)

/** Recommendations from TMDB for the home tab. */
class TmdbRepository(private val client: HttpClient) {

    suspend fun home(language: String = "zh-CN"): Result<TmdbHome> = try {
        coroutineScope {
            val today = currentIsoDate()
            val currentYear = today.take(4).toIntOrNull() ?: 2026
            val nextYearEnd = "${currentYear + 1}-12-31"
            val popularMovies = async { fetch("/movie/popular", language, "movie") }
            val popularShows = async { fetch("/tv/popular", language, "tv") }
            val nowMovies = async { fetch("/movie/now_playing", language, "movie") }
            val nowShows = async { fetch("/tv/airing_today", language, "tv") }
            val upcomingMovies = async { fetch("/movie/upcoming", language, "movie") }
            val upcomingShows = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "first_air_date.gte" to today,
                        "first_air_date.lte" to nextYearEnd,
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to GLOBAL_UPCOMING_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnPopularMovies = async {
                fetch(
                    "/discover/movie",
                    language,
                    "movie",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to DOMESTIC_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnPopularShows = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to DOMESTIC_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnNowMovies = async {
                fetch(
                    "/discover/movie",
                    language,
                    "movie",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "primary_release_year" to currentYear.toString(),
                        "primary_release_date.lte" to today,
                        "region" to "CN",
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to DOMESTIC_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnNowShows = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "first_air_date_year" to currentYear.toString(),
                        "first_air_date.lte" to today,
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to DOMESTIC_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnUpcomingMovies = async {
                fetch(
                    "/discover/movie",
                    language,
                    "movie",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "primary_release_date.gte" to today,
                        "primary_release_date.lte" to nextYearEnd,
                        "region" to "CN",
                        "sort_by" to "popularity.desc",
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val cnUpcomingShows = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "first_air_date.gte" to today,
                        "first_air_date.lte" to nextYearEnd,
                        "sort_by" to "popularity.desc",
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val result = awaitAll(
                popularMovies,
                popularShows,
                nowMovies,
                nowShows,
                upcomingMovies,
                upcomingShows,
                cnPopularMovies,
                cnPopularShows,
                cnNowMovies,
                cnNowShows,
                cnUpcomingMovies,
                cnUpcomingShows,
            )
            val popular = integrateDomestic(
                global = interleave(result[0], result[1]).eligibleCatalogItems(),
                domestic = interleave(result[6], result[7]).trustedDomesticItems(),
            )
            val nowPlaying = integrateDomestic(
                global = interleave(result[2], result[3])
                    .eligibleCatalogItems()
                    .filter { item -> item.releaseDate?.let { it <= today } == true },
                domestic = interleave(result[8], result[9])
                    .trustedDomesticItems()
                    .filter { item -> item.releaseDate?.let { it <= today } == true },
            )
            val upcoming = integrateDomestic(
                global = interleave(result[4], result[5]).eligibleCatalogItems(),
                domestic = interleave(result[10], result[11])
                    .filter { it.popularity >= DOMESTIC_UPCOMING_MIN_POPULARITY },
            )
                .eligibleCatalogItems()
                .filter { item ->
                    item.releaseDate?.let { it >= today && it <= nextYearEnd } == true
                }

            val featured = popular.filter { it.backdropPath != null }.take(5)
            val rows = listOf(
                TmdbRow("热门", popular),
                TmdbRow("正在上映", nowPlaying),
                TmdbRow("即将上映", upcoming),
            ).filter { it.items.isNotEmpty() }

            Result.success(TmdbHome(featured = featured, rows = rows))
        }
    } catch (e: Throwable) {
        Result.failure(EmbyErrorException(e.toError()))
    }

    suspend fun detail(item: TmdbItem, language: String = "zh-CN"): Result<TmdbDetail> = try {
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val dto = client.get("$TMDB_BASE/$type/${item.id}") {
            parameter("language", language)
            parameter("append_to_response", "credits")
        }.body<TmdbDetailDto>()
        val enriched = item.copy(
            title = dto.title ?: dto.name ?: item.title,
            overview = dto.overview?.ifBlank { null } ?: item.overview,
            posterPath = dto.posterPath ?: item.posterPath,
            backdropPath = dto.backdropPath ?: item.backdropPath,
            year = (dto.releaseDate ?: dto.firstAirDate)?.take(4)?.ifBlank { null } ?: item.year,
            releaseDate = (dto.releaseDate ?: dto.firstAirDate)?.ifBlank { null } ?: item.releaseDate,
            rating = dto.voteAverage?.takeIf { it > 0.0 } ?: item.rating,
        )
        Result.success(
            TmdbDetail(
                item = enriched,
                genres = dto.genres.map { it.name },
                runtimeMinutes = dto.runtime?.takeIf { it > 0 }
                    ?: dto.episodeRunTime.firstOrNull { it > 0 },
                numberOfSeasons = dto.numberOfSeasons?.takeIf { it > 0 },
                status = dto.status?.ifBlank { null },
                tagline = dto.tagline?.ifBlank { null },
                cast = dto.credits.cast
                    .filter { it.name.isNotBlank() }
                    .take(16)
                    .map {
                        TmdbPerson(
                            id = it.id,
                            name = it.name,
                            role = it.character?.ifBlank { null },
                            profilePath = it.profilePath,
                        )
                    },
            ),
        )
    } catch (e: Throwable) {
        Result.failure(EmbyErrorException(e.toError()))
    }

    private suspend fun fetch(
        path: String,
        language: String,
        fallbackType: String,
        parameters: Map<String, String> = emptyMap(),
    ): List<TmdbItem> =
        runCatching {
            client.get("$TMDB_BASE$path") {
                parameter("language", language)
                parameters.forEach { (name, value) -> parameter(name, value) }
            }
                .body<TmdbListDto>()
                .results
                .map { it.toItem(fallbackType) }
                .filter { it.title.isNotBlank() }
                .filter { it.posterPath != null || it.backdropPath != null }
        }.getOrDefault(emptyList())

    private fun interleave(first: List<TmdbItem>, second: List<TmdbItem>): List<TmdbItem> =
        buildList {
            repeat(maxOf(first.size, second.size)) { index ->
                first.getOrNull(index)?.let(::add)
                second.getOrNull(index)?.let(::add)
            }
        }.distinctBy { "${it.mediaType}:${it.id}" }

    private fun List<TmdbItem>.eligibleCatalogItems(): List<TmdbItem> =
        filter { item -> item.genreIds.none(BLOCKED_GENRE_IDS::contains) }

    private fun List<TmdbItem>.trustedDomesticItems(): List<TmdbItem> =
        eligibleCatalogItems().filter { item ->
            item.originalLanguage == "zh" &&
                item.voteCount >= DOMESTIC_MIN_VOTES &&
                item.popularity >= DOMESTIC_MIN_POPULARITY
        }

    /** Inserts one domestic title for every two global entries, then removes duplicates. */
    private fun integrateDomestic(
        global: List<TmdbItem>,
        domestic: List<TmdbItem>,
    ): List<TmdbItem> = buildList {
        var domesticIndex = 0
        global.forEachIndexed { index, item ->
            if (index % 2 == 0) {
                domestic.getOrNull(domesticIndex++)?.let(::add)
            }
            add(item)
        }
        while (domesticIndex < domestic.size) {
            add(domestic[domesticIndex++])
        }
    }.distinctBy { "${it.mediaType}:${it.id}" }

    private fun Throwable.toError(): EmbyError = when (this) {
        is ResponseException -> when (response.status.value) {
            401 -> EmbyError.Unauthorized
            in 500..599 -> EmbyError.Server(response.status.value)
            else -> EmbyError.Unknown("HTTP ${response.status.value}")
        }
        else -> EmbyError.Network
    }

    private companion object {
        val BLOCKED_GENRE_IDS = setOf(99, 10763, 10764, 10767)
        val BLOCKED_GENRES_PARAMETER = BLOCKED_GENRE_IDS.joinToString(",")
        const val DOMESTIC_MIN_VOTES = 10
        const val DOMESTIC_MIN_POPULARITY = 3.0
        const val DOMESTIC_UPCOMING_MIN_POPULARITY = 5.0
        const val GLOBAL_UPCOMING_MIN_VOTES = 3
    }
}
