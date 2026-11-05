package com.yfuse.core.data

import com.yfuse.core.model.AiringEpisode
import com.yfuse.core.model.ShowOrigin
import com.yfuse.core.network.TMDB_BASE
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** Three independent metadata sources; their dates never acquire official schedule authority. */
class CalendarMetadataRepository(
    private val primary: suspend (Int, String) -> Result<List<AiringEpisode>>,
    private val tmdbClient: HttpClient,
    private val publicClient: HttpClient,
    val preferences: CalendarMetadataPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private var tvdbSession: Pair<TvdbCalendarCredentials, String>? = null
    private val artworkMutex = Mutex()
    private val artwork = linkedMapOf<String, String>()

    suspend fun backdrop(
        tmdbId: Int,
        movie: Boolean,
    ): String? {
        if (tmdbId <= 0) return null
        val path = "/${if (movie) "movie" else "tv"}/$tmdbId"
        artworkMutex.withLock { artwork[path]?.let { return it } }
        return try {
            withTimeoutOrNull(5_000) {
                val response = tmdbClient.get("$TMDB_BASE$path")
                if (response.status.value != 200) return@withTimeoutOrNull null
                val image =
                    json
                        .parseToJsonElement(response.bodyAsText())
                        .jsonObject
                        .text("backdrop_path")
                        ?.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: return@withTimeoutOrNull null
                artworkMutex.withLock {
                    artwork[path] = image
                    while (artwork.size > 64) artwork.remove(artwork.keys.first())
                }
                image
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun series(
        tmdbId: Int,
        title: String,
    ): Result<List<AiringEpisode>> =
        coroutineScope {
            require(tmdbId > 0)
            val tmdb = async { bounded { primary(tmdbId, title).getOrThrow() } }
            val todb =
                async {
                    if (preferences.todbEnabled) bounded { todbSeries(tmdbId, title) } else null
                }
            val tvdb =
                async {
                    preferences.tvdbCredentials()?.let { credentials ->
                        bounded { tvdbSeries(tmdbId, title, credentials) }
                    }
                }
            val results = listOfNotNull(tmdb.await(), todb.await(), tvdb.await())
            if (results.all(Result<List<AiringEpisode>>::isFailure)) {
                Result.failure(IllegalStateException("暂时无法获取分集排期"))
            } else {
                Result.success(mergeMetadataSchedules(results.map { it.getOrDefault(emptyList()) }))
            }
        }

    private suspend fun bounded(block: suspend () -> List<AiringEpisode>): Result<List<AiringEpisode>> =
        try {
            withTimeoutOrNull(8_500) { Result.success(block()) }
                ?: Result.failure(IllegalStateException("排期数据源请求超时"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Do not retain provider errors: an authentication failure can contain credentials.
            Result.failure(IllegalStateException("排期数据源暂不可用"))
        }

    private suspend fun publicJson(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): String {
        val response =
            publicClient.get("https://theotherdb.org/api$path") {
                parameters.forEach { (key, value) -> parameter(key, value) }
            }
        check(response.status.value == 200)
        return response.bodyAsText().also { check(it.length <= 2_000_000) }
    }

    private suspend fun todbSeries(
        tmdbId: Int,
        title: String,
    ): List<AiringEpisode> =
        coroutineScope {
            val page =
                json
                    .parseToJsonElement(
                        publicJson(
                            "/video/list",
                            mapOf(
                                "video_type" to "tv",
                                "title" to title,
                                "page_size" to "5",
                                "page" to "1",
                            ),
                        ),
                    ).jsonObject
            val candidates = page.objects("items").filter { it.text("video_type") == "tv" }.take(5)
            // Titles are only search hints. A matching TMDB TV identifier is mandatory.
            val matched =
                candidates
                    .map { candidate ->
                        async {
                            val id = candidate.number("video_id") ?: return@async null
                            val external =
                                try {
                                    json.parseToJsonElement(
                                        publicJson(
                                            "/external/list",
                                            mapOf(
                                                "relation_type" to "video_list",
                                                "relation_id" to "$id",
                                            ),
                                        ),
                                    ) as? JsonArray ?: return@async null
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    return@async null
                                }
                            candidate.takeIf {
                                external.any { value ->
                                    val item = value as? JsonObject
                                    item?.text("platform") == "tmdb_id_tv" &&
                                        item.text("value")?.toIntOrNull() == tmdbId
                                }
                            }
                        }
                    }.awaitAll()
                    .filterNotNull()
                    .singleOrNull() ?: return@coroutineScope emptyList()
            val videoId = matched.number("video_id") ?: return@coroutineScope emptyList()
            val seasons =
                (json.parseToJsonElement(publicJson("/video/$videoId/season/all")) as? JsonArray)
                    .orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull { it.number("season_number") }
                    .filter { it > 0 }
                    .distinct()
                    .sortedDescending()
                    .take(3)
            seasons
                .map { season ->
                    async {
                        val episodes =
                            (
                                json.parseToJsonElement(
                                    publicJson("/video/$videoId/season/$season/episode/all"),
                                ) as? JsonArray
                            ).orEmpty()
                                .mapNotNull { it as? JsonObject }
                        check(episodes.size <= 1_000)
                        episodes.mapNotNull { episode ->
                            metadataEpisode(
                                tmdbId,
                                matched.text("video_title") ?: title,
                                season,
                                episode.number("episode_number"),
                                episode.text("episode_title"),
                                episode.text("date_air"),
                                matched.text("image_poster")?.takeIf { it.startsWith("https://") },
                                "TODB",
                                "https://theotherdb.org/video/$videoId",
                                metadataOrigin(matched.strings("origin_countrys")),
                            )
                        }
                    }
                }.awaitAll()
                .flatten()
        }

    private suspend fun tvdbToken(credentials: TvdbCalendarCredentials): String =
        tokenMutex.withLock {
            tvdbSession?.takeIf { it.first == credentials }?.let { return@withLock it.second }
            val response =
                publicClient.post("$TVDB_BASE/login") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        buildJsonObject {
                            put("apikey", credentials.apiKey)
                            if (credentials.pin.isNotBlank()) put("pin", credentials.pin)
                        }.toString(),
                    )
                }
            check(response.status.value == 200)
            val token =
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                    .objectValue("data")
                    ?.text("token")
            check(!token.isNullOrBlank())
            tvdbSession = credentials to token
            token
        }

    private suspend fun tvdbJson(
        path: String,
        credentials: TvdbCalendarCredentials,
    ): JsonObject {
        repeat(2) { attempt ->
            val token = tvdbToken(credentials)
            val response =
                publicClient.get("$TVDB_BASE$path") {
                    expectSuccess = false
                    header("Authorization", "Bearer $token")
                }
            if (response.status.value == 401 && attempt == 0) {
                tokenMutex.withLock { if (tvdbSession?.second == token) tvdbSession = null }
            } else {
                check(response.status.value == 200)
                val body = response.bodyAsText()
                check(body.length <= 2_000_000)
                return json.parseToJsonElement(body).jsonObject
            }
        }
        error("TVDB authentication failed")
    }

    private suspend fun tvdbSeries(
        tmdbId: Int,
        title: String,
        credentials: TvdbCalendarCredentials,
    ): List<AiringEpisode> {
        val response = tmdbClient.get("$TMDB_BASE/tv/$tmdbId/external_ids")
        check(response.status.value == 200)
        val tvdbId =
            json
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
                .number("tvdb_id")
                ?.takeIf { it > 0 } ?: return emptyList()
        val show =
            tvdbJson("/series/$tvdbId/extended?short=true", credentials).objectValue("data") ?: return emptyList()
        val seasonNumbers =
            show
                .objects("seasons")
                .filter { it.objectValue("type")?.text("type") == "official" }
                .mapNotNull { it.number("number") }
                .filter { it > 0 }
                .distinct()
                .sortedDescending()
                .take(3)
        val rows = mutableListOf<AiringEpisode>()
        for (season in seasonNumbers) {
            var complete = false
            for (page in 0 until 4) {
                val result = tvdbJson("/series/$tvdbId/episodes/official?season=$season&page=$page", credentials)
                result.objectValue("data")?.objects("episodes").orEmpty().forEach { episode ->
                    if (episode.number("seasonNumber") != season) return@forEach
                    metadataEpisode(
                        tmdbId,
                        title,
                        season,
                        episode.number("number"),
                        episode.text("name"),
                        episode.text("aired"),
                        show.text("image"),
                        "TVDB",
                        "https://thetvdb.com/dereferrer/series/$tvdbId",
                        metadataOrigin(listOfNotNull(show.text("originalCountry"))),
                    )?.let(rows::add)
                }
                if (result.objectValue("links")?.text("next").isNullOrBlank()) {
                    complete = true
                    break
                }
            }
            check(complete) { "Incomplete TVDB episode pagination" }
        }
        // airsTime is series-level and does not provide an episode-specific IANA timezone.
        // Keep these rows date-only instead of inventing exact release instants.
        return rows
    }

    private companion object {
        const val TVDB_BASE = "https://api4.thetvdb.com/v4"
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject.number(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.objects(key: String): List<JsonObject> =
    (get(key) as? JsonArray).orEmpty().mapNotNull {
        it as? JsonObject
    }

private fun JsonObject.strings(key: String): List<String> =
    (get(key) as? JsonArray).orEmpty().mapNotNull {
        (it as? JsonPrimitive)?.contentOrNull
    }

private fun metadataOrigin(countries: List<String>): ShowOrigin =
    when {
        countries.any { it.uppercase() in setOf("CN", "CHN", "HK", "HKG", "TW", "TWN") } -> ShowOrigin.Domestic
        countries.isNotEmpty() -> ShowOrigin.Foreign
        else -> ShowOrigin.Unknown
    }

private fun metadataEpisode(
    tmdbId: Int,
    title: String,
    season: Int,
    number: Int?,
    episodeTitle: String?,
    date: String?,
    poster: String?,
    provider: String,
    sourceUrl: String,
    origin: ShowOrigin,
): AiringEpisode? {
    if (number == null || number <= 0 || date == null || title.isBlank()) return null
    if (date.length != 10 || runCatching { LocalDate.parse(date) }.isFailure) return null
    return AiringEpisode(
        showTmdbId = tmdbId,
        showTitle = title,
        posterPath = poster,
        seasonNumber = season,
        episodeNumber = number,
        episodeTitle = episodeTitle,
        airDate = date,
        origin = origin,
        scheduleProvider = provider,
        sourceUrl = sourceUrl,
        releaseMode = "DateOnly",
    )
}

/** TMDB first, TODB second, TVDB third. Conflicting dates never silently replace the primary. */
internal fun mergeMetadataSchedules(sources: List<List<AiringEpisode>>): List<AiringEpisode> =
    sources
        .flatten()
        .let { rows ->
            val artwork = rows.filter { !it.posterPath.isNullOrBlank() }.associateBy { it.titleKey }
            rows.distinctBy(AiringEpisode::mediaKey).map { row ->
                if (row.posterPath.isNullOrBlank()) row.copy(posterPath = artwork[row.titleKey]?.posterPath) else row
            }
        }.sortedWith(compareBy({ it.airDate }, { it.seasonNumber }, { it.episodeNumber }))
