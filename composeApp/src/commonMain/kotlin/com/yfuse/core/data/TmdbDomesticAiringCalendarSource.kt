package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.AiringScheduleAuthority
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.TMDB_BASE
import com.yfuse.core.util.currentEpochMillis
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
private data class DomesticCalendarCandidates(
    val results: List<TmdbItemDto>,
)

/** Incomplete refreshes keep older rows; no missing date is inferred from an episode number. */
data class DomesticAiringScheduleResult(
    val episodes: List<AiringEpisode>,
    val complete: Boolean,
)

fun interface DomesticAiringScheduleLookup {
    suspend fun calendar(
        fromDate: String,
        toDate: String,
        forceRefresh: Boolean,
    ): DomesticAiringScheduleResult
}

/** Foreground-only CN discovery: two pages, eight series, one current season per series. */
class TmdbDomesticAiringCalendarSource(
    private val client: HttpClient,
    private val nowEpochMs: () -> Long = ::currentEpochMillis,
    private val totalBudgetMillis: Long = 8_000L,
    private val requestTimeoutMillis: Long = 3_000L,
) : DomesticAiringScheduleLookup {
    private data class Cached(
        val result: DomesticAiringScheduleResult,
        val refreshAt: Long,
    )

    private class Progress {
        val mutex = Mutex()
        val episodes = mutableListOf<AiringEpisode>()
        var complete = true

        suspend fun incomplete() = mutex.withLock { complete = false }

        suspend fun add(rows: List<AiringEpisode>) = mutex.withLock { episodes.addAll(rows) }
    }

    private val refreshMutex = Mutex()
    private val requests = Semaphore(3)
    private val cache = linkedMapOf<Pair<String, String>, Cached>()

    override suspend fun calendar(
        fromDate: String,
        toDate: String,
        forceRefresh: Boolean,
    ): DomesticAiringScheduleResult =
        refreshMutex.withLock {
            val key = fromDate to toDate
            val previous = cache[key]
            if (!forceRefresh && previous != null && nowEpochMs() < previous.refreshAt) {
                return@withLock previous.result
            }
            val progress = Progress()
            val finished =
                withTimeoutOrNull(totalBudgetMillis) {
                    discover(fromDate, toDate, progress)
                    true
                } ?: false
            val complete = finished && progress.complete
            val rows =
                mergeAiringSchedules(
                    if (complete) emptyList() else previous?.result?.episodes.orEmpty(),
                    progress.episodes,
                ).filter { it.airDate in fromDate..toDate }
            val result = DomesticAiringScheduleResult(rows, complete)
            cache[key] = Cached(result, nowEpochMs() + if (complete) 20 * 60_000L else 60_000L)
            while (cache.size > 4) cache.remove(cache.keys.first())
            result
        }

    private suspend fun discover(
        from: String,
        to: String,
        progress: Progress,
    ) = coroutineScope {
        val airing = async { candidates(from, to, premieres = false, progress) }
        val premieres = async { candidates(from, to, premieres = true, progress) }
        val newest = premieres.await()
        val ongoing = airing.await()
        val candidates =
            buildList {
                repeat(maxOf(newest.size, ongoing.size)) { index ->
                    newest.getOrNull(index)?.let(::add)
                    ongoing.getOrNull(index)?.let(::add)
                }
            }.filter { it.id > 0 }.distinctBy { it.id }.take(8)
        candidates
            .map { candidate ->
                async { loadSeries(candidate, from, to, progress) }
            }.awaitAll()
    }

    private suspend fun candidates(
        from: String,
        to: String,
        premieres: Boolean,
        progress: Progress,
    ): List<TmdbItemDto> =
        request(progress) {
            client
                .get("$TMDB_BASE/discover/tv") {
                    expectSuccess = true
                    parameter("language", "zh-CN")
                    parameter("page", 1)
                    parameter("with_origin_country", "CN")
                    parameter("with_original_language", "zh")
                    parameter("without_genres", "99,10763,10764,10767")
                    val dateField = if (premieres) "first_air_date" else "air_date"
                    parameter("$dateField.gte", from)
                    parameter("$dateField.lte", to)
                    parameter("sort_by", if (premieres) "first_air_date.desc" else "popularity.desc")
                }.body<DomesticCalendarCandidates>()
                .results
                .take(20)
        }.orEmpty()

    private suspend fun loadSeries(
        candidate: TmdbItemDto,
        from: String,
        to: String,
        progress: Progress,
    ) {
        val show =
            request(progress) {
                client
                    .get("$TMDB_BASE/tv/${candidate.id}") {
                        expectSuccess = true
                        parameter("language", "zh-CN")
                    }.body<TmdbShowScheduleDto>()
            } ?: return
        // A localized title is not evidence of country, and mismatched ids cannot supply dates.
        if (show.id != candidate.id || show.originCountries.isEmpty()) {
            progress.incomplete()
            return
        }
        if ("CN" !in show.originCountries) return
        val title = show.name?.takeIf(String::isNotBlank) ?: candidate.name?.takeIf(String::isNotBlank) ?: return
        val stubs = listOfNotNull(show.lastEpisode, show.nextEpisode)
        progress.add(stubs.mapNotNull { episode -> episode.toEpisode(show, title, null, from, to) })
        val seasonNumber =
            listOfNotNull(
                show.nextEpisode?.seasonNumber,
                show.lastEpisode?.seasonNumber,
                show.numberOfSeasons,
            ).firstOrNull { it > 0 } ?: run {
                progress.incomplete()
                return
            }
        val season =
            request(progress) {
                client
                    .get("$TMDB_BASE/tv/${show.id}/season/$seasonNumber") {
                        expectSuccess = true
                        parameter("language", "zh-CN")
                    }.body<TmdbSeasonDto>()
            } ?: return
        if (season.seasonNumber != null && season.seasonNumber != seasonNumber) {
            progress.incomplete()
            return
        }
        progress.add(season.episodes.mapNotNull { episode -> episode.toEpisode(show, title, seasonNumber, from, to) })
    }

    private suspend fun <T : Any> request(
        progress: Progress,
        fetch: suspend () -> T,
    ): T? =
        requests.withPermit {
            try {
                withTimeoutOrNull(requestTimeoutMillis) { fetch() }.also {
                    if (it == null) progress.incomplete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                progress.incomplete()
                null
            }
        }

    private fun TmdbEpisodeStubDto.toEpisode(
        show: TmdbShowScheduleDto,
        title: String,
        fallbackSeason: Int?,
        from: String,
        to: String,
    ): AiringEpisode? {
        val date =
            airDate?.takeIf {
                it.length == 10 && it in from..to && runCatching { LocalDate.parse(it) }.isSuccess
            } ?: return null
        val season = (seasonNumber ?: fallbackSeason)?.takeIf { it > 0 } ?: return null
        val episode = episodeNumber?.takeIf { it > 0 } ?: return null
        return AiringEpisode(
            showTmdbId = show.id,
            showTitle = title,
            posterPath = show.posterPath,
            seasonNumber = season,
            episodeNumber = episode,
            episodeTitle = name?.takeIf(String::isNotBlank),
            airDate = date,
            origin = ShowOrigin.Domestic,
            scheduleAuthority = AiringScheduleAuthority.Tmdb,
            scheduleProvider = "TMDB",
            sourceUrl = "https://www.themoviedb.org/tv/${show.id}/season/$season/episode/$episode",
            releaseMode = "DateOnly",
        )
    }
}
