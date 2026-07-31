package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
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
import kotlinx.coroutines.CancellationException
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

/** The two episodes `/tv/{id}` volunteers without asking for a whole season. */
@Serializable
internal data class TmdbEpisodeStubDto(
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    val name: String? = null,
)

/** `/tv/{id}` reduced to what a broadcast calendar needs. */
@Serializable
internal data class TmdbShowScheduleDto(
    val id: Int,
    val name: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("next_episode_to_air") val nextEpisode: TmdbEpisodeStubDto? = null,
    @SerialName("last_episode_to_air") val lastEpisode: TmdbEpisodeStubDto? = null,
)

/** `/tv/{id}/season/{n}` — every episode of one season, with its broadcast date. */
@Serializable
internal data class TmdbSeasonDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    val episodes: List<TmdbEpisodeStubDto> = emptyList(),
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.error(
            category = "tmdb",
            event = "home_failed",
            message = "TMDB home feed failed",
            throwable = e,
        )
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
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.error(
            category = "tmdb",
            event = "detail_failed",
            message = "TMDB detail failed",
            throwable = e,
            attributes = mapOf("mediaType" to item.mediaType),
        )
        Result.failure(EmbyErrorException(e.toError()))
    }

    /**
     * Shows broadcasting around now, and where each one is in its run.
     *
     * Two queries rather than one because TMDB's popularity ranking is global, and a global
     * ranking of currently-airing television returns almost no 国产剧 — the same reason
     * [home] asks for domestic content separately. `air_date` filters on *episode*
     * broadcast dates, which is what "currently airing" means; `first_air_date` would only
     * find shows that *premiered* in the window and miss every series already in its run.
     *
     * Then one `/tv/{id}` per show for its last and next episode. That is a request per
     * show, so the show list is capped — and it is two points per series, which covers a
     * weekly release but not a drama posting daily. Filling those in needs a season fetch
     * per show, which is not worth quadrupling the request count until the calendar has
     * proved itself.
     */
    suspend fun airingCalendar(
        fromDate: String,
        toDate: String,
        language: String = "zh-CN",
    ): Result<List<AiringEpisode>> = try {
        coroutineScope {
            val domestic = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "with_origin_country" to "CN",
                        "with_original_language" to "zh",
                        "air_date.gte" to fromDate,
                        "air_date.lte" to toDate,
                        "sort_by" to "popularity.desc",
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val foreign = async {
                fetch(
                    "/discover/tv",
                    language,
                    "tv",
                    mapOf(
                        "air_date.gte" to fromDate,
                        "air_date.lte" to toDate,
                        "sort_by" to "popularity.desc",
                        "vote_count.gte" to GLOBAL_UPCOMING_MIN_VOTES.toString(),
                        "without_genres" to BLOCKED_GENRES_PARAMETER,
                    ),
                )
            }
            val domesticShows = domestic.await()
            // A Chinese show popular enough to chart globally comes back from both queries;
            // it is domestic, and the foreign list must not claim it a second time.
            val domesticIds = domesticShows.map { it.id }.toSet()
            val shows = interleave(
                domesticShows,
                foreign.await().filterNot { it.id in domesticIds },
            ).take(CALENDAR_MAX_SHOWS)

            val episodes = shows
                .map { show ->
                    async {
                        val dto = schedule(show.id, language) ?: return@async emptyList()
                        val origin = if (show.id in domesticIds) {
                            ShowOrigin.Domestic
                        } else {
                            ShowOrigin.Foreign
                        }
                        val showTitle = dto.name ?: show.title
                        val poster = dto.posterPath ?: show.posterPath
                        // The season the show is currently in. Its whole episode list is
                        // what a 日更 drama needs — last and next alone would leave every
                        // day between them blank on a show that posts one a day. The extra
                        // request per show is affordable because the schedule is fetched
                        // once a day and cached, not on every open.
                        val currentSeason = (dto.nextEpisode ?: dto.lastEpisode)?.seasonNumber
                        val seasonEpisodes = currentSeason
                            ?.let { season(show.id, it, language) }
                            ?.episodes
                            .orEmpty()
                        val stubs = seasonEpisodes.ifEmpty {
                            listOfNotNull(dto.lastEpisode, dto.nextEpisode)
                        }
                        stubs.mapNotNull { stub ->
                            stub.toAiringEpisode(
                                showTmdbId = dto.id,
                                showTitle = showTitle,
                                posterPath = poster,
                                origin = origin,
                                // A season payload omits the season number on its episodes;
                                // it is the season that was asked for.
                                fallbackSeason = currentSeason,
                            )
                        }
                    }
                }
                .awaitAll()
                .flatten()
                // A show's "last episode" can predate the window by weeks when it is between
                // seasons; the calendar only draws the range it was asked for.
                .filter { it.airDate in fromDate..toDate }
                .distinctBy { it.mediaKey }
                .sortedWith(compareBy({ it.airDate }, { it.showTitle }))
            Result.success(episodes)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppLog.warning(
            category = "tmdb",
            event = "calendar_request_failed",
            message = "TMDB airing calendar request failed",
            throwable = e,
        )
        Result.failure(EmbyErrorException(e.toError()))
    }

    private suspend fun schedule(showId: Int, language: String): TmdbShowScheduleDto? =
        try {
            client.get("$TMDB_BASE/tv/$showId") { parameter("language", language) }
                .body<TmdbShowScheduleDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // One unreachable show costs its own rows, not the whole calendar.
            AppLog.warning(
                category = "tmdb",
                event = "show_schedule_failed",
                message = "TMDB show schedule request failed",
                throwable = e,
                attributes = mapOf("showId" to showId.toString()),
            )
            null
        }

    private suspend fun season(showId: Int, seasonNumber: Int, language: String): TmdbSeasonDto? =
        try {
            client.get("$TMDB_BASE/tv/$showId/season/$seasonNumber") {
                parameter("language", language)
            }.body<TmdbSeasonDto>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Falls back to the show's last and next episode, which is still a calendar.
            AppLog.warning(
                category = "tmdb",
                event = "season_request_failed",
                message = "TMDB season request failed",
                throwable = e,
                attributes = mapOf("showId" to showId.toString()),
            )
            null
        }

    private fun TmdbEpisodeStubDto.toAiringEpisode(
        showTmdbId: Int,
        showTitle: String,
        posterPath: String?,
        origin: ShowOrigin,
        fallbackSeason: Int? = null,
    ): AiringEpisode? {
        val date = airDate?.takeIf { it.isNotBlank() } ?: return null
        val season = seasonNumber ?: fallbackSeason ?: return null
        val episode = episodeNumber ?: return null
        if (showTitle.isBlank()) return null
        return AiringEpisode(
            showTmdbId = showTmdbId,
            showTitle = showTitle,
            posterPath = posterPath,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = name?.takeIf { it.isNotBlank() },
            airDate = date,
            origin = origin,
        )
    }

    private suspend fun fetch(
        path: String,
        language: String,
        fallbackType: String,
        parameters: Map<String, String> = emptyMap(),
    ): List<TmdbItem> =
        try {
            client.get("$TMDB_BASE$path") {
                parameter("language", language)
                parameters.forEach { (name, value) -> parameter(name, value) }
            }
                .body<TmdbListDto>()
                .results
                .map { it.toItem(fallbackType) }
                .filter { it.title.isNotBlank() }
                .filter { it.posterPath != null || it.backdropPath != null }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.warning(
                category = "tmdb",
                event = "feed_request_failed",
                message = "TMDB feed request failed: $path",
                throwable = e,
            )
            emptyList()
        }

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

        /** One `/tv/{id}` request each, so the list is capped rather than unbounded. */
        const val CALENDAR_MAX_SHOWS = 24
    }
}
