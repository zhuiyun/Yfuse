package com.yfuse.core.data

import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
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
