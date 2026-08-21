package com.yfuse.core.data

import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringKind
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.model.TmdbDetail
import com.yfuse.core.model.TmdbHome
import com.yfuse.core.model.TmdbItem
import com.yfuse.core.model.TmdbPerson
import com.yfuse.core.model.TmdbRow
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.TMDB_BASE
import com.yfuse.core.util.currentIsoDate
import com.yfuse.core.util.isoDateDaysBefore
import com.yfuse.core.util.pickForDay
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbListDto(
    val results: List<TmdbItemDto> = emptyList(),
)

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
internal data class TmdbGenreDto(
    val name: String,
)

@Serializable
internal data class TmdbCastDto(
    val id: Int,
    val name: String,
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)

@Serializable
internal data class TmdbCreditsDto(
    val cast: List<TmdbCastDto> = emptyList(),
)

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

private fun TmdbItemDto.toItem(fallbackType: String): TmdbItem =
    TmdbItem(
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
class TmdbRepository(
    private val client: HttpClient,
) {
    /**
     * Caps how much of the calendar's fan-out is in flight at once.
     *
     * One refresh asks for up to [CALENDAR_MAX_SHOWS] schedules and a season for each, and
     * they were all launched at once. Nothing went wrong because OkHttp's dispatcher caps
     * itself at five requests per host — but that is an engine default standing in for a
     * decision this code never made, and it stops holding the moment the engine or its
     * configuration changes. TMDB answers a burst past its limit with 429, and a rate-limited
     * calendar fails by going quietly blank rather than by reporting anything.
     *
     * The nearby note that the extra request per show "is affordable" is about the total,
     * which is still true; this is about how many of them happen at the same instant.
     */
    private val calendarRequests = Semaphore(CALENDAR_REQUEST_CONCURRENCY)

    suspend fun home(language: String = "zh-CN"): Result<TmdbHome> =
        try {
            coroutineScope {
                val today = currentIsoDate()
                val recentStart = isoDateDaysBefore(today, RECENT_RELEASE_DAYS)
                val currentYear = today.take(4).toIntOrNull() ?: 2026
                val nextYearEnd = "${currentYear + 1}-12-31"
                val popularMovies = async { fetch("/movie/popular", language, "movie") }
                val popularShows = async { fetch("/tv/popular", language, "tv") }
                val nowMovies = async { fetch("/movie/now_playing", language, "movie") }
                val nowShows = async { fetch("/tv/airing_today", language, "tv") }
                // `/movie/upcoming` is region-relative and can return dates that have already
                // passed locally. Discover gives this shelf an explicit future window instead.
                val upcomingMovies =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "primary_release_date.gte" to today,
                                "primary_release_date.lte" to nextYearEnd,
                                "sort_by" to "popularity.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                                "include_video" to "false",
                            ),
                        )
                    }
                val upcomingShows =
                    async {
                        fetch(
                            "/discover/tv",
                            language,
                            "tv",
                            mapOf(
                                "first_air_date.gte" to today,
                                "first_air_date.lte" to nextYearEnd,
                                "sort_by" to "popularity.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                            ),
                        )
                    }
                val cnPopularMovies =
                    async {
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
                val cnPopularShows =
                    async {
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
                val cnNowMovies =
                    async {
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
                val cnNowShows =
                    async {
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
                val cnUpcomingMovies =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "with_origin_country" to CHINESE_ORIGIN_COUNTRIES_PARAMETER,
                                "with_original_language" to "zh",
                                "primary_release_date.gte" to today,
                                "primary_release_date.lte" to nextYearEnd,
                                "sort_by" to "popularity.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                                "include_video" to "false",
                            ),
                        )
                    }
                val cnUpcomingShows =
                    async {
                        fetch(
                            "/discover/tv",
                            language,
                            "tv",
                            mapOf(
                                "with_origin_country" to CHINESE_ORIGIN_COUNTRIES_PARAMETER,
                                "with_original_language" to "zh",
                                "first_air_date.gte" to today,
                                "first_air_date.lte" to nextYearEnd,
                                "sort_by" to "popularity.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                            ),
                        )
                    }
                val latestMovies =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "primary_release_date.gte" to recentStart,
                                "primary_release_date.lte" to today,
                                "sort_by" to "primary_release_date.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                                "include_video" to "false",
                            ),
                        )
                    }
                val latestShows =
                    async {
                        fetch(
                            "/discover/tv",
                            language,
                            "tv",
                            mapOf(
                                "first_air_date.gte" to recentStart,
                                "first_air_date.lte" to today,
                                "sort_by" to "first_air_date.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                            ),
                        )
                    }
                val cnLatestMovies =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "with_origin_country" to CHINESE_ORIGIN_COUNTRIES_PARAMETER,
                                "with_original_language" to "zh",
                                "primary_release_date.gte" to recentStart,
                                "primary_release_date.lte" to today,
                                "sort_by" to "primary_release_date.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                                "include_video" to "false",
                            ),
                        )
                    }
                val cnLatestShows =
                    async {
                        fetch(
                            "/discover/tv",
                            language,
                            "tv",
                            mapOf(
                                "with_origin_country" to CHINESE_ORIGIN_COUNTRIES_PARAMETER,
                                "with_original_language" to "zh",
                                "first_air_date.gte" to recentStart,
                                "first_air_date.lte" to today,
                                "sort_by" to "first_air_date.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                                "include_adult" to "false",
                            ),
                        )
                    }
                val result =
                    awaitAll(
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
                        latestMovies,
                        latestShows,
                        cnLatestMovies,
                        cnLatestShows,
                    )
                val popular =
                    integrateDomestic(
                        global = interleave(result[0], result[1]).eligibleCatalogItems(),
                        domestic = interleave(result[6], result[7]).trustedDomesticItems(),
                    )
                val nowPlaying =
                    integrateDomestic(
                        global =
                            interleave(result[2], result[3])
                                .eligibleCatalogItems()
                                .filter { item -> item.releaseDate?.let { it <= today } == true },
                        domestic =
                            interleave(result[8], result[9])
                                .trustedDomesticItems()
                                .filter { item -> item.releaseDate?.let { it <= today } == true },
                    )
                val upcoming =
                    integrateDomestic(
                        global = interleave(result[4], result[5]).eligibleCatalogItems(),
                        // Unreleased titles routinely have no votes and very low popularity. Those
                        // are not signs of bad metadata here, so keep the dated Chinese catalogue.
                        domestic = interleave(result[10], result[11]).eligibleCatalogItems(),
                    ).eligibleCatalogItems()
                        .filter { item ->
                            item.releaseDate?.let { it >= today && it <= nextYearEnd } == true
                        }
                val latest =
                    integrateDomestic(
                        global = interleave(result[12], result[13]).eligibleCatalogItems(),
                        domestic = interleave(result[14], result[15]).eligibleCatalogItems(),
                    ).filter { item ->
                        item.releaseDate?.let { it >= recentStart && it <= today } == true
                    }.sortedWith(
                        compareByDescending<TmdbItem> { it.releaseDate.orEmpty() }
                            .thenByDescending { it.originalLanguage == "zh" }
                            .thenByDescending { it.popularity },
                    )

                // The pool 今日精选 rotates through, not a shortlist anyone sees all of. Only
                // one is shown per day, so a handful would come back round inside a fortnight.
                val featured = popular.filter { it.backdropPath != null }.take(FEATURED_POOL)
                val enrichedFeatured = enrichFeaturedRuntimes(featured, today, language)
                val rows =
                    listOf(
                        TmdbRow("热门", popular),
                        TmdbRow("最新上线", latest),
                        TmdbRow("正在上映", nowPlaying),
                        TmdbRow("即将上映", upcoming),
                    ).filter { it.items.isNotEmpty() }

                // Sixteen queries and not one row between them is not a thin catalogue — it is
                // TMDB being unreachable, which on a mainland connection without a proxy is the
                // normal case. Reported as a failure so the screen can say so: it used to return
                // an empty success, and the home tab rendered silent blank shelves instead.
                if (rows.isEmpty() && featured.isEmpty()) {
                    AppLog.warning(
                        category = "tmdb",
                        event = "home_unavailable",
                        message = "Every TMDB feed came back empty; treating as unreachable",
                    )
                    Result.failure(EmbyErrorException(EmbyError.Network))
                } else {
                    Result.success(TmdbHome(featured = enrichedFeatured, rows = rows))
                }
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

    suspend fun detail(
        item: TmdbItem,
        language: String = "zh-CN",
    ): Result<TmdbDetail> =
        try {
            val type = if (item.mediaType == "tv") "tv" else "movie"
            val dto =
                client
                    .get("$TMDB_BASE/$type/${item.id}") {
                        parameter("language", language)
                        parameter("append_to_response", "credits")
                    }.body<TmdbDetailDto>()
            val runtimeMinutes =
                dto.runtime?.takeIf { it > 0 }
                    ?: dto.episodeRunTime.firstOrNull { it > 0 }
            val enriched =
                item.copy(
                    title = dto.title ?: dto.name ?: item.title,
                    overview = dto.overview?.ifBlank { null } ?: item.overview,
                    posterPath = dto.posterPath ?: item.posterPath,
                    backdropPath = dto.backdropPath ?: item.backdropPath,
                    year = (dto.releaseDate ?: dto.firstAirDate)?.take(4)?.ifBlank { null } ?: item.year,
                    releaseDate = (dto.releaseDate ?: dto.firstAirDate)?.ifBlank { null } ?: item.releaseDate,
                    rating = dto.voteAverage?.takeIf { it > 0.0 } ?: item.rating,
                    runtimeMinutes = runtimeMinutes ?: item.runtimeMinutes,
                )
            Result.success(
                TmdbDetail(
                    item = enriched,
                    genres = dto.genres.map { it.name },
                    runtimeMinutes = runtimeMinutes,
                    numberOfSeasons = dto.numberOfSeasons?.takeIf { it > 0 },
                    status = dto.status?.ifBlank { null },
                    tagline = dto.tagline?.ifBlank { null },
                    cast =
                        dto.credits.cast
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
     * List endpoints do not include runtime. Enrich only the eight titles that the carousel
     * can actually show, keeping the broader recommendation pool and shelves lightweight.
     */
    private suspend fun enrichFeaturedRuntimes(
        featured: List<TmdbItem>,
        today: String,
        language: String,
    ): List<TmdbItem> =
        coroutineScope {
            val first = featured.pickForDay(today) ?: return@coroutineScope featured
            val visible = (listOf(first) + featured.filterNot { it.id == first.id }).take(8)
            val permits = Semaphore(4)
            val runtimes =
                visible
                    .map { item ->
                        async {
                            val runtime =
                                try {
                                    permits.withPermit { fetchRuntimeMinutes(item, language) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    null
                                }
                            (item.mediaType to item.id) to runtime
                        }
                    }.awaitAll()
                    .toMap()
            featured.map { item ->
                item.copy(
                    runtimeMinutes =
                        runtimes[item.mediaType to item.id]
                            ?: item.runtimeMinutes,
                )
            }
        }

    private suspend fun fetchRuntimeMinutes(
        item: TmdbItem,
        language: String,
    ): Int? {
        val type = if (item.mediaType == "tv") "tv" else "movie"
        val dto =
            client
                .get("$TMDB_BASE/$type/${item.id}") {
                    parameter("language", language)
                }.body<TmdbDetailDto>()
        return dto.runtime?.takeIf { it > 0 }
            ?: dto.episodeRunTime.firstOrNull { it > 0 }
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
    ): Result<List<AiringEpisode>> =
        try {
            coroutineScope {
                val domestic =
                    async {
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
                val foreign =
                    async {
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
                // Films, which the calendar had none of. A release date is the same kind of
                // fact as a broadcast date and is the other half of "what's out this week";
                // leaving it out meant the page answered half the question it is opened for.
                val domesticFilms =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "with_origin_country" to "CN",
                                "with_original_language" to "zh",
                                "primary_release_date.gte" to fromDate,
                                "primary_release_date.lte" to toDate,
                                "region" to "CN",
                                "sort_by" to "popularity.desc",
                                "without_genres" to BLOCKED_GENRES_PARAMETER,
                            ),
                        )
                    }
                val foreignFilms =
                    async {
                        fetch(
                            "/discover/movie",
                            language,
                            "movie",
                            mapOf(
                                "primary_release_date.gte" to fromDate,
                                "primary_release_date.lte" to toDate,
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
                val shows =
                    interleave(
                        domesticShows,
                        foreign.await().filterNot { it.id in domesticIds },
                    ).take(CALENDAR_MAX_SHOWS)

                val episodes =
                    shows
                        .map { show ->
                            async {
                                calendarRequests.withPermit {
                                    val dto = schedule(show.id, language) ?: return@withPermit emptyList()
                                    val origin =
                                        if (show.id in domesticIds) {
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
                                    val seasonEpisodes =
                                        currentSeason
                                            ?.let { season(show.id, it, language) }
                                            ?.episodes
                                            .orEmpty()

                                    fun stubsOf(source: List<TmdbEpisodeStubDto>) =
                                        source
                                            .mapNotNull { stub ->
                                                stub.toAiringEpisode(
                                                    showTmdbId = dto.id,
                                                    showTitle = showTitle,
                                                    posterPath = poster,
                                                    origin = origin,
                                                    // A season payload omits the season number on its episodes;
                                                    // it is the season that was asked for.
                                                    fallbackSeason = currentSeason,
                                                )
                                            }.filter { it.airDate in fromDate..toDate }

                                    // Fall back on what the *season* yielded, not on whether the season
                                    // list was empty.
                                    //
                                    // This is where 国产剧 were disappearing. A Chinese web drama's TMDB
                                    // season is routinely listed with every episode named and none of
                                    // them dated — the only dates on the record are the show-level
                                    // `next_episode_to_air` / `last_episode_to_air`. The season list was
                                    // therefore non-empty, `ifEmpty` never fired, every undated stub was
                                    // dropped for having no air date, and the show contributed nothing
                                    // at all. Asking whether anything usable came back covers both that
                                    // case and the empty one it was written for.
                                    stubsOf(seasonEpisodes).ifEmpty {
                                        stubsOf(listOfNotNull(dto.lastEpisode, dto.nextEpisode))
                                    }
                                }
                            }
                        }.awaitAll()
                        .flatten()
                        .distinctBy { it.mediaKey }

                val domesticFilmIds = domesticFilms.await().map { it.id }.toSet()
                val films =
                    interleave(
                        domesticFilms.await(),
                        foreignFilms.await().filterNot { it.id in domesticFilmIds },
                    ).take(CALENDAR_MAX_FILMS)
                        .mapNotNull { film ->
                            film.toAiringFilm(
                                origin =
                                    if (film.id in domesticFilmIds) {
                                        ShowOrigin.Domestic
                                    } else {
                                        ShowOrigin.Foreign
                                    },
                            )
                        }.filter { it.airDate in fromDate..toDate }
                        .distinctBy { it.mediaKey }

                Result.success(
                    (episodes + films).sortedWith(compareBy({ it.airDate }, { it.showTitle })),
                )
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

    private suspend fun schedule(
        showId: Int,
        language: String,
    ): TmdbShowScheduleDto? =
        try {
            client
                .get("$TMDB_BASE/tv/$showId") { parameter("language", language) }
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

    private suspend fun season(
        showId: Int,
        seasonNumber: Int,
        language: String,
    ): TmdbSeasonDto? =
        try {
            client
                .get("$TMDB_BASE/tv/$showId/season/$seasonNumber") {
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

    /** A film as a dated calendar row. Its title is the row; it has no coordinate. */
    private fun TmdbItem.toAiringFilm(origin: ShowOrigin): AiringEpisode? {
        val date = releaseDate?.takeIf { it.isNotBlank() } ?: return null
        if (title.isBlank()) return null
        return AiringEpisode(
            showTmdbId = id,
            showTitle = title,
            posterPath = posterPath,
            // Zero rather than null: the field is not optional, and no film has a
            // coordinate, so any constant reads the same. [AiringEpisode.kind] is what the
            // UI actually branches on.
            seasonNumber = 0,
            episodeNumber = 0,
            episodeTitle = null,
            airDate = date,
            origin = origin,
            kind = AiringKind.Movie,
        )
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
            client
                .get("$TMDB_BASE$path") {
                    parameter("language", language)
                    parameters.forEach { (name, value) -> parameter(name, value) }
                }.body<TmdbListDto>()
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
                attributes =
                    mapOf(
                        "answered" to (e is ResponseException).toString(),
                        "status" to (
                            (e as? ResponseException)
                                ?.response
                                ?.status
                                ?.value
                                ?.toString()
                                ?: "none"
                        ),
                    ),
            )
            // Stays local to this shelf on purpose. These run as siblings under one
            // `coroutineScope`, so rethrowing here cancels every feed that *did* answer —
            // one unreachable shelf would take the whole screen down with it. Whether TMDB
            // is reachable at all is a question about the set, and `home` answers it.
            emptyList()
        }

    private fun interleave(
        first: List<TmdbItem>,
        second: List<TmdbItem>,
    ): List<TmdbItem> =
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
    ): List<TmdbItem> =
        buildList {
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

    private fun Throwable.toError(): EmbyError =
        when (this) {
            is ResponseException ->
                when (response.status.value) {
                    401 -> EmbyError.Unauthorized
                    in 500..599 -> EmbyError.Server(response.status.value)
                    else -> EmbyError.Unknown("HTTP ${response.status.value}")
                }
            else -> EmbyError.Network
        }

    private companion object {
        val BLOCKED_GENRE_IDS = setOf(99, 10763, 10764, 10767)
        val BLOCKED_GENRES_PARAMETER = BLOCKED_GENRE_IDS.joinToString(",")
        const val CHINESE_ORIGIN_COUNTRIES_PARAMETER = "CN|HK|TW"
        const val DOMESTIC_MIN_VOTES = 10
        const val DOMESTIC_MIN_POPULARITY = 3.0
        const val RECENT_RELEASE_DAYS = 30

        /** Calendar discovery still needs to bound the number of shows it expands. */
        const val GLOBAL_UPCOMING_MIN_VOTES = 3

        /** Three weeks of daily picks before one repeats. */
        const val FEATURED_POOL = 21

        /** One `/tv/{id}` request each, so the list is capped rather than unbounded. */
        const val CALENDAR_MAX_SHOWS = 24

        /**
         * Comfortably inside TMDB's published allowance while still refreshing the calendar
         * in a couple of rounds rather than one at a time.
         */
        const val CALENDAR_REQUEST_CONCURRENCY = 6

        /**
         * Fewer than the shows, because a film is one row and a 日更 drama is fourteen.
         * Matching the show budget would make the calendar mostly cinema listings.
         */
        const val CALENDAR_MAX_FILMS = 12
    }
}
