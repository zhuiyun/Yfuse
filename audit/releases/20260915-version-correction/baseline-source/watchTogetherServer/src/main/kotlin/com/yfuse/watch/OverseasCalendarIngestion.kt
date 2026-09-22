package com.yfuse.watch

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

private val overseasJson = Json { ignoreUnknownKeys = true }

internal data class StructuredCalendarSource(
    val type: String,
    val publisher: String,
    val sourceUrl: String,
    val capturedAt: String,
    val contentHash: String,
    val episodes: Map<Int, CalendarEpisode>,
    val airTime: String? = null,
    val timeZoneId: String? = null,
)

/** Pure parsers kept separate from transport so provider payloads can be regression-tested. */
internal object OverseasScheduleParser {
    fun discoverTvmazeShows(
        body: String,
        today: LocalDate,
        config: OverseasCalendarConfig,
    ): List<CalendarIngestionShow> {
        val root = runCatching { overseasJson.parseToJsonElement(body).jsonArray }.getOrNull() ?: return emptyList()
        val earliest = today.minusDays(config.pastDays.toLong())
        val latest = today.plusDays(config.futureDays.toLong())
        return root.asSequence()
            .mapNotNull { episodeNode ->
                val episode = episodeNode.asObject() ?: return@mapNotNull null
                val show = episode.embeddedShow() ?: episode["show"]?.asObject() ?: return@mapNotNull null
                val airDate = episode.string("airdate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    ?: return@mapNotNull null
                if (airDate !in earliest..latest) return@mapNotNull null
                val number = episode.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
                val season = episode.int("season")?.takeIf { it > 0 } ?: return@mapNotNull null
                val showId = show.int("id")?.takeIf { it > 0 } ?: return@mapNotNull null
                val showType = show.string("type") ?: return@mapNotNull null
                if (config.allowedShowTypes.none { it.equals(showType, ignoreCase = true) }) return@mapNotNull null
                val webChannel = show["webChannel"]?.asObject()
                val network = show["network"]?.asObject()
                if (webChannel != null && !config.includeGlobalStreaming) return@mapNotNull null
                val country = (network ?: webChannel)?.get("country")?.asObject()
                val countryCode = country?.string("code")?.uppercase()
                    ?: if (webChannel != null) "GLOBAL" else null
                if (
                    config.countryCodes.isNotEmpty() &&
                    countryCode != "GLOBAL" &&
                    countryCode !in config.countryCodes.map(String::uppercase)
                ) return@mapNotNull null
                TvmazeDiscoveryEpisode(
                    showId = showId,
                    imdbId = show["externals"]?.asObject()?.string("imdb"),
                    title = show.string("name") ?: return@mapNotNull null,
                    year = show.string("premiered")?.take(4)?.toIntOrNull() ?: airDate.year,
                    seasonNumber = season,
                    airDate = airDate,
                    airTime = episode.string("airtime") ?: show["schedule"]?.asObject()?.string("time"),
                    timeZoneId = country?.string("timezone"),
                    platform = (webChannel ?: network)?.string("name"),
                    countryCode = countryCode,
                    weight = show.int("weight")?.coerceIn(0, 1_000) ?: 0,
                )
            }
            .groupBy { it.showId to it.seasonNumber }
            .values
            .asSequence()
            .map { rows ->
                val first = rows.first()
                val platform = first.platform?.takeIf(String::isNotBlank) ?: "TVmaze"
                val airTime = rows.mapNotNull(TvmazeDiscoveryEpisode::airTime).distinct().singleOrNull()
                val zone = first.timeZoneId?.takeIf { runCatching { ZoneId.of(it) }.isSuccess }
                val perDay = rows.groupingBy { it.airDate }.eachCount().values
                CalendarIngestionShow(
                    title = first.title,
                    year = first.year,
                    tvmazeId = first.showId,
                    imdbId = first.imdbId,
                    seasonNumber = first.seasonNumber,
                    airTime = airTime.takeIf { zone != null },
                    timeZoneId = zone.takeIf { airTime != null },
                    platforms = listOf(platform),
                    accessTier = "Unknown",
                    origin = "Foreign",
                    availabilityRegion = first.countryCode,
                    releaseMode = if (perDay.any { it > 1 }) "Batch" else if (airTime == null) "DateOnly" else "Weekly",
                    discoveryWeight = rows.maxOf(TvmazeDiscoveryEpisode::weight),
                    sources = emptyList(),
                )
            }
            .groupBy(CalendarIngestionShow::tvmazeId)
            .values
            .map { seasons -> seasons.maxBy(CalendarIngestionShow::seasonNumber) }
            .sortedWith(
                compareByDescending<CalendarIngestionShow>(CalendarIngestionShow::discoveryWeight)
                    .thenBy(CalendarIngestionShow::title),
            )
            .take(config.maxShows)
    }

    fun parseTmdbSeason(
        body: String,
        sourceUrl: String,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val root = runCatching { overseasJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val episodes = root["episodes"]?.asArray().orEmpty().mapNotNull { node ->
            val episode = node.asObject() ?: return@mapNotNull null
            val number = episode.int("episode_number")?.takeIf { it > 0 } ?: return@mapNotNull null
            val date = episode.string("air_date")?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
                ?: return@mapNotNull null
            number to CalendarEpisode(number, date)
        }.toMap()
        if (episodes.isEmpty()) return null
        return StructuredCalendarSource(
            type = "TmdbSchedule",
            publisher = "TMDB",
            sourceUrl = sourceUrl,
            capturedAt = capturedAt,
            contentHash = body.overseasSha256(),
            episodes = episodes,
        )
    }

    fun parseTvmazeShowId(body: String): Int? =
        runCatching {
            overseasJson.parseToJsonElement(body).jsonObject.int("id")?.takeIf { it > 0 }
        }.getOrNull()

    fun parseTvmazeEpisodes(
        body: String,
        seasonNumber: Int,
        showId: Int,
        timeZoneId: String?,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val root = runCatching { overseasJson.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        val zone = timeZoneId?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val parsed = root.mapNotNull { node ->
            val episode = node.asObject() ?: return@mapNotNull null
            if (episode.int("season") != seasonNumber) return@mapNotNull null
            val number = episode.int("number")?.takeIf { it > 0 } ?: return@mapNotNull null
            val airstamp = episode.string("airstamp")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            val declaredDate = episode.string("airdate")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val localDate = airstamp?.toInstant()?.let { instant -> zone?.let { instant.atZone(it).toLocalDate() } }
                ?: declaredDate ?: return@mapNotNull null
            val releaseUtc = airstamp?.toInstant()?.toString()
            val releaseBeijing = airstamp?.toInstant()?.atZone(BEIJING_ZONE)?.toOffsetDateTime()?.toString()
            number to CalendarEpisode(number, localDate.toString(), releaseUtc, releaseBeijing)
        }.toMap()
        if (parsed.isEmpty()) return null
        val localTimes = root.mapNotNull { node ->
            val episode = node.asObject() ?: return@mapNotNull null
            if (episode.int("season") != seasonNumber) return@mapNotNull null
            val airstamp = episode.string("airstamp")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            airstamp?.toInstant()?.let { instant -> zone?.let { instant.atZone(it).toLocalTime().withSecond(0).withNano(0).toString() } }
                ?: episode.string("airtime")
        }.distinct()
        return StructuredCalendarSource(
            type = "TvmazeSchedule",
            publisher = "TVmaze",
            sourceUrl = "https://www.tvmaze.com/shows/$showId",
            capturedAt = capturedAt,
            contentHash = body.overseasSha256(),
            episodes = parsed,
            airTime = localTimes.singleOrNull()?.takeIf { zone != null },
            timeZoneId = zone?.id.takeIf { localTimes.size == 1 },
        )
    }

    private data class TvmazeDiscoveryEpisode(
        val showId: Int,
        val imdbId: String?,
        val title: String,
        val year: Int,
        val seasonNumber: Int,
        val airDate: LocalDate,
        val airTime: String?,
        val timeZoneId: String?,
        val platform: String?,
        val countryCode: String?,
        val weight: Int,
    )
}

internal object OverseasEvidenceGate {
    fun compile(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        sources: List<StructuredCalendarSource>,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? {
        if (sources.isEmpty()) return null
        val tmdb = sources.firstOrNull { it.type == "TmdbSchedule" }
        val tvmaze = sources.firstOrNull { it.type == "TvmazeSchedule" }
        val shared = tmdb?.episodes?.keys.orEmpty().intersect(tvmaze?.episodes?.keys.orEmpty())
        if (shared.any { tmdb?.episodes?.get(it)?.airDate != tvmaze?.episodes?.get(it)?.airDate }) return null

        val episodes = linkedMapOf<Int, CalendarEpisode>()
        tmdb?.episodes?.toSortedMap()?.forEach { (number, episode) -> episodes[number] = episode }
        tvmaze?.episodes?.toSortedMap()?.forEach { (number, episode) -> episodes[number] = episode }
        if (episodes.isEmpty()) return null
        val (authority, confidence) = when {
            tmdb != null && tvmaze != null && shared.isNotEmpty() -> "Verified" to 85
            tvmaze != null -> "Estimated" to 70
            tmdb != null -> "Estimated" to 65
            else -> return null
        }
        val evidence = buildList {
            sources.forEach { source ->
                add(
                    CalendarEvidence(
                        type = source.type,
                        publisher = source.publisher,
                        sourceUrl = source.sourceUrl,
                        capturedAt = source.capturedAt,
                        contentHash = source.contentHash,
                        extractionMethod = "structured-provider-json",
                    ),
                )
            }
            add(
                CalendarEvidence(
                    type = "TmdbIdentity",
                    publisher = "TMDB",
                    sourceUrl = identity.evidenceUrl,
                    capturedAt = generatedAt,
                    contentHash = identity.evidenceHash,
                    extractionMethod = "strict-id-match",
                ),
            )
        }.distinctBy { it.type to it.sourceUrl }
        val timed = tvmaze?.takeIf { it.airTime != null && it.timeZoneId != null }
        return CalendarSeries(
            tmdbId = identity.tmdbId,
            title = identity.title.ifBlank { show.title },
            seasonNumber = show.seasonNumber,
            posterPath = identity.posterPath ?: show.posterPath,
            airTime = timed?.airTime,
            timeZoneId = timed?.timeZoneId,
            platforms = show.platforms.ifEmpty { listOf("TVmaze") },
            accessTier = show.accessTier,
            origin = "Foreign",
            availabilityRegion = show.availabilityRegion,
            releaseMode = show.releaseMode,
            sourceUrl = (tvmaze ?: tmdb)?.sourceUrl ?: identity.evidenceUrl,
            revision = revision,
            updatedAt = generatedAt,
            authority = authority,
            confidence = confidence,
            evidence = evidence,
            episodes = episodes.values.toList(),
        )
    }
}

private fun JsonElement.asObject(): JsonObject? = this as? JsonObject
private fun JsonElement.asArray(): JsonArray? = this as? JsonArray
private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
private fun JsonObject.embeddedShow(): JsonObject? = this["_embedded"]?.asObject()?.get("show")?.asObject()
private fun String.overseasSha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

private val BEIJING_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
