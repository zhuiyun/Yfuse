package com.yfuse.core.data

import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.TMDB_BASE
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
    mediaType = mediaType ?: fallbackType,
    rating = voteAverage?.takeIf { it > 0.0 },
)

/** Recommendations from TMDB for the home tab. */
class TmdbRepository(private val client: HttpClient) {

    suspend fun home(language: String = "zh-CN"): Result<TmdbHome> = try {
        coroutineScope {
            val trending = async { fetch("/trending/all/week", language, "movie") }
            val movies = async { fetch("/movie/popular", language, "movie") }
            val shows = async { fetch("/tv/popular", language, "tv") }
            val (trend, pop, tv) = awaitAll(trending, movies, shows)

            val featured = trend.filter { it.backdropPath != null }.take(5)
            val rows = listOf(
                TmdbRow("本周趋势", trend),
                TmdbRow("热门电影", pop),
                TmdbRow("热门剧集", tv),
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

    private suspend fun fetch(path: String, language: String, fallbackType: String): List<TmdbItem> =
        runCatching {
            client.get("$TMDB_BASE$path") { parameter("language", language) }
                .body<TmdbListDto>()
                .results
                .map { it.toItem(fallbackType) }
                .filter { it.title.isNotBlank() }
        }.getOrDefault(emptyList())

    private fun Throwable.toError(): EmbyError = when (this) {
        is ResponseException -> when (response.status.value) {
            401 -> EmbyError.Unauthorized
            in 500..599 -> EmbyError.Server(response.status.value)
            else -> EmbyError.Unknown("HTTP ${response.status.value}")
        }
        else -> EmbyError.Network
    }
}
