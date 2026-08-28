package com.yfuse.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.absoluteValue

private val ingestionJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

@Serializable
internal data class CalendarIngestionStatus(
    val state: String = "idle",
    val lastStartedAt: String? = null,
    val lastFinishedAt: String? = null,
    val changed: Boolean = false,
    val configuredShows: Int = 0,
    val discoveredShows: Int = 0,
    val domesticDiscoveredShows: Int = 0,
    val domesticCandidateShows: Int = 0,
    val tmdbDomesticCandidates: Int = 0,
    val platformDomesticCandidates: Int = 0,
    val domesticEvidenceMatchedShows: Int = 0,
    val overseasDiscoveredShows: Int = 0,
    val publishedShows: Int = 0,
    val message: String? = null,
)

internal object CalendarIngestionHealth {
    @Volatile
    private var value = CalendarIngestionStatus()

    fun snapshot(): CalendarIngestionStatus = value

    fun running(
        configuredShows: Int,
        discoveredShows: Int,
    ) {
        value =
            CalendarIngestionStatus(
                state = "running",
                lastStartedAt = Instant.now().toString(),
                configuredShows = configuredShows,
                discoveredShows = discoveredShows,
            )
    }

    fun succeeded(
        changed: Boolean,
        publishedShows: Int,
    ) {
        value =
            value.copy(
                state = "success",
                lastFinishedAt = Instant.now().toString(),
                changed = changed,
                publishedShows = publishedShows,
                message = null,
            )
    }

    fun discovered(
        domestic: Int,
        overseas: Int,
        candidates: DomesticCandidateCounts = DomesticCandidateCounts(),
    ) {
        value =
            value.copy(
                discoveredShows = domestic + overseas,
                domesticDiscoveredShows = domestic,
                domesticCandidateShows = candidates.merged,
                tmdbDomesticCandidates = candidates.tmdb,
                platformDomesticCandidates = candidates.platform,
                domesticEvidenceMatchedShows = candidates.evidenceMatched,
                overseasDiscoveredShows = overseas,
            )
    }

    fun failed(failure: Throwable) {
        value =
            value.copy(
                state = "failed",
                lastFinishedAt = Instant.now().toString(),
                changed = false,
                message = failure.message?.take(240) ?: failure::class.simpleName,
            )
    }
}

@Serializable
internal data class CalendarIngestionConfig(
    val refreshMinutes: Int = 30,
    val verifiedAccounts: List<VerifiedCalendarAccount> = emptyList(),
    val ocrProviders: List<CalendarOcrProviderConfig> = emptyList(),
    val pageRenderer: CalendarPageRendererConfig? = null,
    val discoveryFeeds: List<CalendarDiscoveryFeed> = emptyList(),
    val domesticCandidates: DomesticCandidateDiscoveryConfig = DomesticCandidateDiscoveryConfig(),
    val overseas: OverseasCalendarConfig = OverseasCalendarConfig(),
    val shows: List<CalendarIngestionShow> = emptyList(),
)

@Serializable
internal data class OverseasCalendarConfig(
    val enabled: Boolean = false,
    val tvmazeFullScheduleUrl: String = "https://api.tvmaze.com/schedule/full",
    val pastDays: Int = 7,
    val futureDays: Int = 45,
    val maxShows: Int = 200,
    val countryCodes: List<String> = listOf("US", "GB", "CA", "AU", "JP", "KR"),
    val includeGlobalStreaming: Boolean = true,
    val allowedShowTypes: List<String> = listOf("Scripted", "Animation"),
)

@Serializable
internal data class CalendarPageRendererConfig(
    val endpoint: String,
    val apiKeyEnvironment: String? = null,
)

/**
 * One official index/archive replaces a hand-maintained entry for every new show. The default
 * pattern recognizes links such as 《剧名》追剧日历; deployments can supply a stricter first
 * capture group when a platform uses different wording.
 */
@Serializable
internal data class CalendarDiscoveryFeed(
    val type: String,
    val platform: String? = null,
    val publisherId: String? = null,
    val publisher: String,
    val url: String,
    val titlePattern: String = DEFAULT_DISCOVERY_TITLE_PATTERN,
    val year: Int? = null,
    val seasonNumber: Int = 1,
    val airTime: String = "12:00",
    val timeZoneId: String = "Asia/Shanghai",
    val platforms: List<String>,
    val accessTier: String = "Member",
    val maxShows: Int = 50,
)

@Serializable
internal data class VerifiedCalendarAccount(
    val publisherId: String,
    val publisher: String,
    val profileUrl: String,
)

@Serializable
internal data class CalendarOcrProviderConfig(
    val id: String,
    val endpoint: String,
    val apiKeyEnvironment: String? = null,
    val protocol: String = OCR_PROTOCOL_BRIDGE,
    val model: String? = null,
    val engine: Int? = null,
    val language: String? = null,
    val pollIntervalMillis: Long = 2_000,
    val pollTimeoutSeconds: Long = 120,
)

@Serializable
private data class PaddleOcrSubmitRequest(
    val fileUrl: String,
    val model: String,
    val optionalPayload: JsonObject,
)

internal data class PaddleOcrJobSnapshot(
    val state: String,
    val resultUrl: String? = null,
    val errorMessage: String? = null,
)

internal object PaddleOcrResponseParser {
    fun submittedJobId(body: String): String? =
        runCatching {
            ingestionJson.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("jobId")
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    fun jobSnapshot(body: String): PaddleOcrJobSnapshot? =
        runCatching {
            val data = ingestionJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return null
            val state = data["state"]?.jsonPrimitive?.content?.lowercase()?.takeIf(String::isNotBlank) ?: return null
            PaddleOcrJobSnapshot(
                state = state,
                resultUrl =
                    data["resultUrl"]
                        ?.jsonObject
                        ?.get("jsonUrl")
                        ?.jsonPrimitive
                        ?.content
                        ?.takeIf(String::isNotBlank),
                errorMessage = data["errorMsg"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
            )
        }.getOrNull()

    fun isSubmitQueueFull(body: String): Boolean =
        runCatching {
            ingestionJson.parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() == PADDLE_QUEUE_FULL_CODE
        }.getOrDefault(false)

    fun extractMarkdownText(jsonLines: String): String? =
        runCatching {
            val results =
                jsonLines.lineSequence()
                    .filter(String::isNotBlank)
                    .mapNotNull { line ->
                        ingestionJson.parseToJsonElement(line).jsonObject["result"]?.jsonObject
                    }.toList()
            val markdown =
                results.asSequence()
                    .flatMap { result ->
                        result["layoutParsingResults"]?.jsonArray.orEmpty().asSequence()
                    }.mapNotNull { layout ->
                        layout.jsonObject["markdown"]
                            ?.jsonObject
                            ?.get("text")
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }.joinToString("\n")
                    .takeIf(String::isNotBlank)
            markdown ?: results.asSequence()
                .flatMap { result -> result["ocrResults"]?.jsonArray.orEmpty().asSequence() }
                .flatMap { ocrResult ->
                    ocrResult.jsonObject["prunedResult"]
                        ?.jsonObject
                        ?.get("rec_texts")
                        ?.jsonArray
                        .orEmpty()
                        .asSequence()
                }.mapNotNull { text -> text.jsonPrimitive.content.trim().takeIf(String::isNotBlank) }
                .joinToString("\n")
                .takeIf(String::isNotBlank)
        }.getOrNull()
}

internal object OcrSpaceResponseParser {
    fun extractText(body: String): String? =
        runCatching {
            val root = ingestionJson.parseToJsonElement(body).jsonObject
            val errored = root["IsErroredOnProcessing"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (errored) return null
            root["ParsedResults"]
                ?.jsonArray
                .orEmpty()
                .asSequence()
                .mapNotNull { result ->
                    result.jsonObject["ParsedText"]
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }.joinToString("\n")
                .takeIf(String::isNotBlank)
        }.getOrNull()

    fun failureSummary(body: String): String =
        runCatching {
            val root = ingestionJson.parseToJsonElement(body).jsonObject
            val exitCode = root["OCRExitCode"]?.jsonPrimitive?.content ?: "unknown"
            val error =
                listOf("ErrorMessage", "ErrorDetails")
                    .mapNotNull { key -> root[key]?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString(";")
                    .replace(Regex("[\\r\\n\\t]"), " ")
                    .take(300)
                    .ifBlank { "none" }
            "exit=$exitCode error=$error"
        }.getOrDefault("unparseable-response")
}

@Serializable
internal data class CalendarIngestionShow(
    val title: String,
    val year: Int,
    val tmdbId: Int? = null,
    val tvmazeId: Int? = null,
    val imdbId: String? = null,
    val seasonNumber: Int = 1,
    val posterPath: String? = null,
    val airTime: String? = "12:00",
    val timeZoneId: String? = "Asia/Shanghai",
    val platforms: List<String>,
    val accessTier: String = "Member",
    val origin: String = "Domestic",
    val availabilityRegion: String? = null,
    val releaseMode: String = "Scheduled",
    val discoveryWeight: Int = 0,
    val sources: List<CalendarSourceConfig>,
)

@Serializable
internal data class CalendarSourceConfig(
    val type: String,
    val platform: String? = null,
    val publisherId: String? = null,
    val publisher: String,
    val url: String,
    val imageUrls: List<String> = emptyList(),
)

internal data class ParsedCalendarSource(
    val source: CalendarSourceConfig,
    val capturedAt: String,
    val contentHash: String,
    val episodes: Map<Int, String>,
    val ocrConsensus: Boolean,
    val ocrAgreement: OcrAgreement = if (ocrConsensus) OcrAgreement.Exact else OcrAgreement.None,
)

internal data class ResolvedCalendarIdentity(
    val tmdbId: Int,
    val title: String,
    val posterPath: String?,
    val evidenceUrl: String,
    val evidenceHash: String,
)

internal enum class OcrAgreement {
    None,
    Exact,
    PartialSubset,
    CoordinateIntersection,
    SemanticCorroboration,
}

internal data class OcrReading(
    val providerId: String,
    val text: String,
    val episodes: Map<Int, String>,
)

private data class OcrConsensusCapture(
    val imageUrl: String,
    val episodes: Map<Int, String>,
    val readingHashes: List<Pair<String, String>>,
    val agreement: OcrAgreement,
)

/**
 * Converts independently captured official evidence into the only object the public route can
 * sign. Conflicting dates reject the whole title: publishing less data is safer than silently
 * selecting whichever official page happened to be fetched last.
 */
internal object CalendarEvidenceGate {
    fun compile(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        sources: List<ParsedCalendarSource>,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? {
        if (sources.isEmpty()) return null
        val coordinates = linkedMapOf<Int, String>()
        sources.forEach { parsed ->
            parsed.episodes.forEach { (episode, date) ->
                val previous = coordinates.putIfAbsent(episode, date)
                if (previous != null && previous != date) return null
            }
        }
        if (coordinates.isEmpty()) return null

        val sourceKinds = sources.map { it.source.type }.toSet()
        val officialSourceCount = sources.map { it.source.url }.distinct().size
        var confidence =
            when {
                "PlatformPage" in sourceKinds -> 60
                "VerifiedAccount" in sourceKinds -> 45
                else -> 0
            }
        confidence += 25 // explicit date + episode coordinates were parsed
        confidence += 10 // a strict TMDB identity was resolved
        confidence += sources.maxOfOrNull { ocrConfidenceBonus(it.ocrAgreement) } ?: 0
        if (officialSourceCount >= 2) confidence += 25
        confidence = confidence.coerceAtMost(100)
        if (confidence < 60) return null
        val authority = if (confidence >= 80) "Official" else "Estimated"
        val evidence =
            buildList {
                sources.forEach { parsed ->
                    add(
                        CalendarEvidence(
                            type = parsed.source.type,
                            publisher = parsed.source.publisher,
                            sourceUrl = parsed.source.url,
                            capturedAt = parsed.capturedAt,
                            contentHash = parsed.contentHash,
                            extractionMethod =
                                if (parsed.ocrConsensus) {
                                    "official-text+dual-ocr-${parsed.ocrAgreement.name.lowercase()}"
                                } else {
                                    "official-text"
                                },
                        ),
                    )
                    if (parsed.ocrConsensus) {
                        add(
                            CalendarEvidence(
                                type = "OcrConsensus",
                                publisher = parsed.source.publisher,
                                sourceUrl = parsed.source.url,
                                capturedAt = parsed.capturedAt,
                                contentHash = parsed.contentHash,
                                extractionMethod =
                                    when (parsed.ocrAgreement) {
                                        OcrAgreement.Exact -> "two-ocr-exact-coordinate-agreement"
                                        OcrAgreement.PartialSubset -> "two-ocr-subset-coordinate-agreement"
                                        OcrAgreement.CoordinateIntersection -> "two-ocr-coordinate-intersection"
                                        OcrAgreement.SemanticCorroboration -> "two-ocr-schedule-rule-corroboration"
                                        OcrAgreement.None -> "two-independent-ocr-results-agree"
                                    },
                            ),
                        )
                    }
                }
                add(
                    CalendarEvidence(
                        type = "TmdbIdentity",
                        publisher = "TMDB",
                        sourceUrl = identity.evidenceUrl,
                        capturedAt = generatedAt,
                        contentHash = identity.evidenceHash,
                        extractionMethod = "exact-id-or-title-year-match",
                    ),
                )
            }.distinctBy { Triple(it.type, it.sourceUrl, it.contentHash) }

        if (evidence.size > MAX_EVIDENCE_PER_SERIES) return null

        return CalendarSeries(
            tmdbId = identity.tmdbId,
            title = identity.title.ifBlank { show.title },
            seasonNumber = show.seasonNumber,
            posterPath = identity.posterPath ?: show.posterPath,
            airTime = show.airTime,
            timeZoneId = show.timeZoneId,
            platforms = show.platforms.distinct(),
            accessTier = show.accessTier,
            sourceUrl = sources.maxBy { it.capturedAt }.source.url,
            revision = revision,
            updatedAt = generatedAt,
            authority = authority,
            confidence = confidence,
            evidence = evidence,
            episodes =
                coordinates.entries
                    .sortedBy(Map.Entry<Int, String>::key)
                    .map { CalendarEpisode(it.key, it.value) },
        )
    }
}

/** Parser for the compact date/episode grammar used by mainland tracking-calendar posts. */
internal object ChineseScheduleParser {
    private val datePattern = Regex("(?:(20\\d{2})年)?(1[0-2]|0?[1-9])月(3[01]|[12]\\d|0?[1-9])日")
    private val episodePattern =
        Regex("(?:第|更新|上线|会员|SVIP|vip|VIP)?\\s*((?:\\d{1,3}\\s*[、,，~～—\\-至到]\\s*)*\\d{1,3})\\s*集")
    private val dayAtStartPattern =
        Regex("^(3[01]|[12]\\d|0?[1-9])(?=\\s|SVIP|VIP|会员|周|$)(.*)$", RegexOption.IGNORE_CASE)
    private val monthHeaderPattern = Regex("^(1[0-2]|0?[1-9])月$")
    private val weekdayPattern = Regex("周([一二三四五六日天])")

    fun parse(
        raw: String,
        defaultYear: Int,
        accessTier: String? = null,
    ): Map<Int, String> {
        val explicit = parseExplicitDates(raw, defaultYear)
        if (accessTier == null) return explicit
        val grid = parseCalendarGrid(raw, defaultYear, accessTier)
        return mergeWithoutConflict(listOf(explicit, grid)) ?: emptyMap()
    }

    private fun parseExplicitDates(
        raw: String,
        defaultYear: Int,
    ): Map<Int, String> {
        val text =
            raw.replace('\u00a0', ' ')
                .replace("\r", " ")
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
        val dates = datePattern.findAll(text).toList()
        val result = linkedMapOf<Int, String>()
        dates.forEachIndexed { index, match ->
            val year = match.groupValues[1].toIntOrNull() ?: defaultYear
            val month = match.groupValues[2].toInt()
            val day = match.groupValues[3].toInt()
            val date = runCatching { LocalDate.of(year, month, day).toString() }.getOrNull() ?: return@forEachIndexed
            val end = dates.getOrNull(index + 1)?.range?.first ?: minOf(text.length, match.range.last + 100)
            val block = text.substring(match.range.last + 1, end)
            episodePattern.findAll(block).forEach { episodeMatch ->
                if (isUpdateCount(episodeMatch.value)) return@forEach
                expandEpisodes(episodeMatch.groupValues[1]).forEach { episode ->
                    val previous = result.putIfAbsent(episode, date)
                    if (previous != null && previous != date) return emptyMap()
                }
            }
        }
        return result
    }

    private fun parseCalendarGrid(
        raw: String,
        defaultYear: Int,
        accessTier: String,
    ): Map<Int, String> {
        val plain =
            raw.replace(Regex("(?is)<[^>]+>"), " ")
                .replace("\r", "\n")
                .replace('\u00a0', ' ')
        var currentMonth =
            Regex("(?<!\\d)(1[0-2]|0?[1-9])月")
                .find(plain)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: chineseMonthIn(plain)
                ?: return emptyMap()
        var currentDate: LocalDate? = null
        var pendingPreferredTier = false
        val result = linkedMapOf<Int, String>()

        plain.lineSequence().map(String::trim).filter(String::isNotBlank).forEach { line ->
            monthHeaderPattern.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()?.let { month ->
                currentMonth = month
                currentDate = null
                pendingPreferredTier = false
                return@forEach
            }
            chineseMonthHeader(line)?.let { month ->
                currentMonth = month
                currentDate = null
                pendingPreferredTier = false
                return@forEach
            }

            weekdayPattern.find(line)?.groupValues?.get(1)?.let { weekday ->
                currentDate = alignToWeekday(currentDate, weekday)
            }

            val dayMatch = dayAtStartPattern.matchEntire(line)
            if (dayMatch != null) {
                val remainder = dayMatch.groupValues[2]
                val otherBareNumbers = Regex("(?<![:\\d])\\d{1,2}(?![:\\d])").findAll(remainder).count()
                if (otherBareNumbers >= 2 && "集" !in remainder) {
                    currentDate = null
                    pendingPreferredTier = false
                    return@forEach
                }
                val day = dayMatch.groupValues[1].toInt()
                currentDate = runCatching { LocalDate.of(defaultYear, currentMonth, day) }.getOrNull()
            }

            val markerOnly = line.matches(Regex("(?i)^(?:VIP|会员|VIP会员)$"))
            val fragments = preferredTierFragments(line, accessTier).toMutableList()
            if (pendingPreferredTier && fragments.isEmpty()) fragments += line
            if (currentDate != null) {
                fragments.forEach { fragment -> episodePattern.findAll(fragment).forEach { episodeMatch ->
                    if (isUpdateCount(episodeMatch.value)) return@forEach
                    expandEpisodes(episodeMatch.groupValues[1]).forEach { episode ->
                        val date = currentDate!!.toString()
                        val previous = result.putIfAbsent(episode, date)
                        if (previous != null && previous != date) return emptyMap()
                    }
                } }
            }
            pendingPreferredTier = markerOnly && isPreferredTierLine(line, accessTier)
        }
        return result
    }

    private fun isPreferredTierLine(
        line: String,
        accessTier: String,
    ): Boolean =
        when (accessTier) {
            "Member" -> "会员" in line && "非会员" !in line && !line.contains("SVIP", ignoreCase = true)
            "SviP" -> line.contains("SVIP", ignoreCase = true)
            "Free" -> "非会员" in line || "免费" in line
            else ->
                "会员" !in line &&
                    !line.contains("SVIP", ignoreCase = true) &&
                    !line.contains("VIP", ignoreCase = true)
        }

    private fun preferredTierFragments(
        line: String,
        accessTier: String,
    ): List<String> =
        when (accessTier) {
            "Member" ->
                Regex(
                    "(?i)(?<!S)(?:VIP)?会员.{0,100}?(?=SVIP|东方卫视|CCTV|非会员|$)",
                ).findAll(line).map(MatchResult::value).toList()
            "SviP" ->
                Regex("(?i)SVIP.{0,100}?(?=(?<!S)(?:VIP)?会员|东方卫视|CCTV|非会员|$)")
                    .findAll(line).map(MatchResult::value).toList()
            "Free" ->
                Regex("(?:非会员|免费).{0,100}?(?=SVIP|(?<!S)(?:VIP)?会员|东方卫视|CCTV|$)")
                    .findAll(line).map(MatchResult::value).toList()
            else -> if (isPreferredTierLine(line, accessTier)) listOf(line) else emptyList()
        }

    private fun alignToWeekday(
        date: LocalDate?,
        chineseWeekday: String,
    ): LocalDate? {
        date ?: return null
        val target =
            when (chineseWeekday) {
                "一" -> 1
                "二" -> 2
                "三" -> 3
                "四" -> 4
                "五" -> 5
                "六" -> 6
                else -> 7
            }
        if (date.dayOfWeek.value == target) return date
        val daysAhead = (target - date.dayOfWeek.value + 7) % 7
        return date.plusDays(daysAhead.toLong())
    }

    private fun chineseMonthIn(text: String): Int? =
        CHINESE_MONTHS.entries.firstOrNull { (label, _) -> label in text }?.value

    private fun chineseMonthHeader(line: String): Int? = CHINESE_MONTHS[line]

    private fun isUpdateCount(value: String): Boolean =
        value.replace(Regex("\\s+"), "").matches(Regex("(?:更新|上线)\\d{1,3}集"))

    private fun expandEpisodes(raw: String): List<Int> {
        val normalized = raw.replace(Regex("[至到~～—-]"), "-")
        val range = Regex("^(\\d{1,3})\\s*-\\s*(\\d{1,3})$").matchEntire(normalized.trim())
        if (range != null) {
            val first = range.groupValues[1].toInt()
            val last = range.groupValues[2].toInt()
            return if (first in 1..500 && last in first..500 && last - first <= 100) {
                (first..last).toList()
            } else {
                emptyList()
            }
        }
        return normalized
            .split(Regex("[、,，]"))
            .mapNotNull(String::toIntOrNull)
            .filter { it in 1..500 }
            .distinct()
    }

    private val CHINESE_MONTHS =
        mapOf(
            "壹月" to 1,
            "贰月" to 2,
            "叁月" to 3,
            "肆月" to 4,
            "伍月" to 5,
            "陆月" to 6,
            "柒月" to 7,
            "捌月" to 8,
            "玖月" to 9,
            "拾月" to 10,
            "拾壹月" to 11,
            "拾贰月" to 12,
        )
}

internal data class OcrConsensusResolution(
    val episodes: Map<Int, String> = emptyMap(),
    val agreement: OcrAgreement = OcrAgreement.None,
    val conflict: Boolean = false,
)

internal object OcrConfidenceGate {
    fun resolve(
        readings: List<OcrReading>,
        defaultYear: Int,
        accessTier: String,
    ): OcrConsensusResolution {
        if (readings.size != MAX_OCR_PROVIDERS || readings.map(OcrReading::providerId).distinct().size != readings.size) {
            return OcrConsensusResolution()
        }
        val first = readings[0]
        val second = readings[1]
        val dailyGrids = readings.map { dailyGridCoordinates(it.text, defaultYear, accessTier) }
        if (dailyGrids.all { it.isNotEmpty() } && dailyGrids.distinct().size == 1) {
            return OcrConsensusResolution(dailyGrids.first(), OcrAgreement.SemanticCorroboration)
        }
        val sharedEpisodes = first.episodes.keys intersect second.episodes.keys
        if (sharedEpisodes.any { first.episodes[it] != second.episodes[it] }) {
            return OcrConsensusResolution(conflict = true)
        }
        if (first.episodes.isNotEmpty() && first.episodes == second.episodes) {
            return OcrConsensusResolution(first.episodes, OcrAgreement.Exact)
        }

        val nonEmpty = readings.filter { it.episodes.isNotEmpty() }
        if (nonEmpty.size == 2) {
            val smaller = nonEmpty.minBy { it.episodes.size }
            val larger = nonEmpty.maxBy { it.episodes.size }
            val smallerIsSubset = smaller.episodes.all { (episode, date) -> larger.episodes[episode] == date }
            if (smaller.episodes.size >= MIN_PARTIAL_OCR_COORDINATES && smallerIsSubset) {
                return OcrConsensusResolution(larger.episodes, OcrAgreement.PartialSubset)
            }
            val intersection =
                sharedEpisodes
                    .filter { first.episodes[it] == second.episodes[it] }
                    .associateWith { first.episodes.getValue(it) }
            if (intersection.size >= MIN_PARTIAL_OCR_COORDINATES) {
                return OcrConsensusResolution(intersection, OcrAgreement.CoordinateIntersection)
            }
        }

        val fullReleaseDates = readings.map { fullReleaseDate(it.text, defaultYear, accessTier) }
        if (fullReleaseDates.all { it != null } && fullReleaseDates.distinct().size == 1) {
            val episodeCount = readings.maxOfOrNull { fullSeriesEpisodeCount(it.text) } ?: 0
            if (episodeCount in 2..MAX_CALENDAR_EPISODES) {
                val airDate = fullReleaseDates.first()!!.toString()
                return OcrConsensusResolution(
                    episodes = (1..episodeCount).associateWith { airDate },
                    agreement = OcrAgreement.SemanticCorroboration,
                )
            }
        }

        val candidate = nonEmpty.maxByOrNull { it.episodes.size }?.episodes.orEmpty()
        if (candidate.isNotEmpty() && semanticallyCorroborated(candidate, readings, defaultYear, accessTier)) {
            return OcrConsensusResolution(candidate, OcrAgreement.SemanticCorroboration)
        }
        return OcrConsensusResolution()
    }

    private fun semanticallyCorroborated(
        candidate: Map<Int, String>,
        readings: List<OcrReading>,
        defaultYear: Int,
        accessTier: String,
    ): Boolean {
        val fullReleaseDates = readings.map { fullReleaseDate(it.text, defaultYear, accessTier) }
        if (fullReleaseDates.all { it != null } && fullReleaseDates.distinct().size == 1) {
            val date = fullReleaseDates.first()!!
            if (isContiguousFromOne(candidate) && candidate.values.all { it == date.toString() }) return true
        }

        val cadence = readings.map { scheduleCadence(it.text, defaultYear, accessTier) }
        if (cadence.all { it != null } && cadence.distinct().size == 1) {
            return candidateMatchesCadence(candidate, cadence.first()!!)
        }
        return false
    }

    private fun fullReleaseDate(
        raw: String,
        defaultYear: Int,
        accessTier: String,
    ): LocalDate? {
        val text = normalizeOcrText(raw)
        return SEMANTIC_DATE_PATTERN.findAll(text).firstNotNullOfOrNull { match ->
            val end = minOf(text.length, match.range.last + 100)
            val block = text.substring(match.range.first, end)
            val releaseMarker = listOf("看全集", "全集上线", "一次性上线").firstOrNull(block::contains)
            val statement = releaseMarker?.let { block.substringBefore(it) }.orEmpty()
            val preferredTier = accessTier != "Member" || ("会员" in statement && "非会员" !in statement)
            if (preferredTier && releaseMarker != null) {
                match.toLocalDate(defaultYear)
            } else {
                null
            }
        }
    }

    private fun fullSeriesEpisodeCount(raw: String): Int =
        FULL_SERIES_RANGE_PATTERN.findAll(normalizeOcrText(raw))
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 2..MAX_CALENDAR_EPISODES }
            .maxOrNull()
            ?: 0

    private fun scheduleCadence(
        raw: String,
        defaultYear: Int,
        accessTier: String,
    ): ScheduleCadence? {
        if (accessTier != "Member") return null
        val text = normalizeOcrText(raw)
        val dates = SEMANTIC_DATE_PATTERN.findAll(text).toList()
        val blocks =
            dates.mapIndexed { index, dateMatch ->
                val end = dates.getOrNull(index + 1)?.range?.first ?: text.length
                dateMatch to text.substring(dateMatch.range.last + 1, end)
            }
        val premiere =
            blocks.firstNotNullOfOrNull { (dateMatch, block) ->
                if ("会员" !in block || "非会员" in block) return@firstNotNullOfOrNull null
                PREMIERE_COUNT_IN_BLOCK.find(block)?.let { dateMatch to it }
            } ?: return null
        val daily =
            blocks.firstNotNullOfOrNull { (dateMatch, block) ->
                if ("会员" !in block || "非会员" in block) return@firstNotNullOfOrNull null
                DAILY_COUNT_IN_BLOCK.find(block)?.let { dateMatch to it }
            } ?: return null
        val premiereDate = premiere.first.toLocalDate(defaultYear) ?: return null
        val dailyDate = daily.first.toLocalDate(defaultYear) ?: return null
        val premiereCount = premiere.second.groupValues[1].toIntOrNull() ?: return null
        val dailyCount = daily.second.groupValues[1].toIntOrNull() ?: return null
        if (premiereCount !in 1..20 || dailyCount !in 1..20 || dailyDate.isBefore(premiereDate)) return null
        return ScheduleCadence(premiereDate, premiereCount, dailyDate, dailyCount)
    }

    private fun candidateMatchesCadence(
        candidate: Map<Int, String>,
        cadence: ScheduleCadence,
    ): Boolean {
        if (!isContiguousFromOne(candidate)) return false
        val parsed = candidate.mapValues { (_, date) -> runCatching { LocalDate.parse(date) }.getOrNull() ?: return false }
        val maxDate = parsed.values.maxOrNull() ?: return false
        val expected = linkedMapOf<Int, String>()
        var episode = 1
        repeat(cadence.premiereCount) { expected[episode++] = cadence.premiereDate.toString() }
        var date = cadence.dailyStartDate
        while (!date.isAfter(maxDate) && episode <= candidate.size) {
            repeat(cadence.dailyCount) {
                if (episode <= candidate.size) expected[episode++] = date.toString()
            }
            date = date.plusDays(1)
        }
        return expected == candidate.toSortedMap()
    }

    private fun isContiguousFromOne(candidate: Map<Int, String>): Boolean =
        candidate.isNotEmpty() && candidate.keys.sorted() == (1..candidate.size).toList()

    private fun dailyGridCoordinates(
        raw: String,
        defaultYear: Int,
        accessTier: String,
    ): Map<Int, String> {
        if (accessTier != "Member") return emptyMap()
        val text = normalizeOcrText(raw)
        val premiereDate =
            SEMANTIC_DATE_PATTERN.findAll(text).firstNotNullOfOrNull { match ->
                val end = minOf(text.length, match.range.last + 140)
                val block = text.substring(match.range.first, end)
                if (
                    "会员" in block &&
                    "非会员" !in block.substringBefore("每日") &&
                    "每日" in block &&
                    PREMIERE_COUNT_IN_BLOCK.containsMatchIn(block) &&
                    DAILY_COUNT_IN_BLOCK.containsMatchIn(block)
                ) {
                    match.toLocalDate(defaultYear)
                } else {
                    null
                }
            } ?: return emptyMap()
        val ranges =
            DAILY_GRID_EPISODE_PATTERN.findAll(text)
                .mapNotNull { match ->
                    val first = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val last = match.groupValues[2].toIntOrNull() ?: first
                    (first..last).takeIf { first in 1..MAX_CALENDAR_EPISODES && last in first..MAX_CALENDAR_EPISODES }
                }.distinctBy { it.first to it.last }
                .toList()
        val groups =
            ranges.filterNot { candidate ->
                ranges.any { other ->
                    other != candidate &&
                        candidate.first >= other.first &&
                        candidate.last <= other.last &&
                        (other.last - other.first) > (candidate.last - candidate.first)
                }
            }.sortedBy(IntRange::first)
        if (groups.size < 2) return emptyMap()
        val flattened = groups.flatMap(IntRange::toList)
        if (flattened != (1..flattened.size).toList()) return emptyMap()
        return buildMap {
            groups.forEachIndexed { dayOffset, episodes ->
                val date = premiereDate.plusDays(dayOffset.toLong()).toString()
                episodes.forEach { episode -> put(episode, date) }
            }
        }
    }

    private fun normalizeOcrText(raw: String): String =
        raw.replace(Regex("(?is)<[^>]+>"), " ")
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")

    private fun MatchResult.toLocalDate(defaultYear: Int): LocalDate? =
        runCatching {
            LocalDate.of(
                groupValues[1].toIntOrNull() ?: defaultYear,
                groupValues[2].toInt(),
                groupValues[3].toInt(),
            )
        }.getOrNull()

    private data class ScheduleCadence(
        val premiereDate: LocalDate,
        val premiereCount: Int,
        val dailyStartDate: LocalDate,
        val dailyCount: Int,
    )

    private val SEMANTIC_DATE_PATTERN =
        Regex("(?:(20\\d{2})年)?(1[0-2]|0?[1-9])月(3[01]|[12]\\d|0?[1-9])日")
    private val PREMIERE_COUNT_IN_BLOCK =
        Regex("(?:更新|首更|上线)\\s*(\\d{1,2})\\s*集", RegexOption.IGNORE_CASE)
    private val DAILY_COUNT_IN_BLOCK =
        Regex("每日[^。；;]{0,40}?更新\\s*(\\d{1,2})\\s*集", RegexOption.IGNORE_CASE)
    private val FULL_SERIES_RANGE_PATTERN =
        Regex("(?:第)?1\\s*[-~～—至到]\\s*(\\d{1,3})\\s*集")
    private val DAILY_GRID_EPISODE_PATTERN =
        Regex("(?:第)?(\\d{1,3})(?:\\s*[-~～—至到]\\s*(\\d{1,3}))?\\s*集")
}

private data class CapturedCalendarDiscoveryFeed(
    val feed: CalendarDiscoveryFeed,
    val contents: List<String>,
)

private data class DomesticDiscoveryBatch(
    val shows: List<CalendarIngestionShow>,
    val counts: DomesticCandidateCounts,
)

private class CalendarIngestionRuntime(
    private val configFile: File,
    private val outputFile: File?,
    private val tmdbToken: String?,
    private val scheduleStore: CalendarScheduleStore,
) {
    private val http =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    private val discoveryRequests = Semaphore(DISCOVERY_REQUEST_CONCURRENCY)
    private val showRequests = Semaphore(SHOW_INGESTION_CONCURRENCY)
    private val sourceRequests = Semaphore(SOURCE_INGESTION_CONCURRENCY)
    private val ocrRequests = Semaphore(OCR_REQUEST_CONCURRENCY)
    private val paddleRequests = Semaphore(PADDLE_REQUEST_CONCURRENCY)
    private val ocrSpaceRequests = Semaphore(OCR_SPACE_REQUEST_CONCURRENCY)

    suspend fun runOnce(): Boolean =
        withContext(Dispatchers.IO) {
            val config = ingestionJson.decodeFromString<CalendarIngestionConfig>(configFile.readText())
            validateConfig(config)
            val generatedAt = Instant.now().toString()
            val existing = readExistingPublication()
            val today = LocalDate.now(ZoneOffset.UTC)
            CalendarIngestionHealth.running(config.shows.size, discoveredShows = 0)
            val domesticDiscovery = discoverShows(config, today)
            val domesticDiscoveredShows = domesticDiscovery.shows
            val overseasDiscoveredShows = discoverOverseasShows(config, today)
            val discoveredShows = domesticDiscoveredShows + overseasDiscoveredShows
            CalendarIngestionHealth.discovered(
                domestic = domesticDiscoveredShows.size,
                overseas = overseasDiscoveredShows.size,
                candidates = domesticDiscovery.counts,
            )
            val ingestionShows = mergeIngestionShows(config.shows + discoveredShows)
            val fallbackSchedules =
                (existing?.schedules.orEmpty() + DEFAULT_CALENDAR_SCHEDULES)
                    .distinctBy(CalendarSeries::tmdbId)
            val provisionalRevision = nextRevision(existing?.revision, today)
            val refreshedSchedules =
                coroutineScope {
                    ingestionShows
                        .map { show ->
                            async {
                                showRequests.withPermit {
                                    collectShowSchedule(
                                        show = show,
                                        config = config,
                                        fallbackSchedules = fallbackSchedules,
                                        revision = provisionalRevision,
                                        generatedAt = generatedAt,
                                    )
                                }
                            }
                        }.awaitAll()
                        .filterNotNull()
                        .distinctBy(CalendarSeries::tmdbId)
                }
            // Discovery archive pages naturally roll older posts out. Keep their last verified
            // schedule through the useful catch-up window instead of deleting it from the next
            // complete publication merely because the source link left the first page.
            val retentionDate = today.minusDays(DISCOVERED_SCHEDULE_RETENTION_DAYS.toLong()).toString()
            val refreshedIds = refreshedSchedules.map(CalendarSeries::tmdbId).toSet()
            val retainedSchedules =
                existing
                    ?.schedules
                    .orEmpty()
                    .filterNot { it.tmdbId in refreshedIds }
                    // Archive pages roll domestic posts off their first page, so those need a
                    // grace window. Overseas discovery is a complete filtered snapshot: keeping
                    // titles absent from it would preserve excluded news/sports indefinitely.
                    .filter { it.origin == "Domestic" }
                    .filter { schedule ->
                        schedule.episodes.maxOfOrNull(CalendarEpisode::airDate)?.let { it >= retentionDate } == true
                    }
                    .map { it.copy(revision = provisionalRevision) }
            val schedules = (refreshedSchedules + retainedSchedules).distinctBy(CalendarSeries::tmdbId)
            if (schedules.isEmpty()) {
                CalendarIngestionHealth.succeeded(changed = false, publishedShows = 0)
                return@withContext false
            }
            val contentFingerprint = semanticFingerprint(schedules)
            val oldFingerprint = existing?.schedules?.let(::semanticFingerprint)
            if (contentFingerprint == oldFingerprint) {
                CalendarIngestionHealth.succeeded(changed = false, publishedShows = schedules.size)
                return@withContext false
            }
            val publication = CalendarPublication(provisionalRevision, generatedAt, schedules)
            validateCalendarPublication(publication)
            scheduleStore.replace(publication)
            runCatching { writeAtomically(publication) }
                .onFailure { failure ->
                    if (scheduleStore === NoOpCalendarScheduleStore) throw failure
                    System.err.println("calendar JSON snapshot failed: ${failure.message}")
                }
            CalendarIngestionHealth.succeeded(changed = true, publishedShows = schedules.size)
            true
        }

    private suspend fun discoverShows(
        config: CalendarIngestionConfig,
        today: LocalDate,
    ): DomesticDiscoveryBatch =
        coroutineScope {
            val capturedFeeds = async { captureDiscoveryFeeds(config) }
            val tmdbCandidates = async { discoverTmdbDomesticCandidates(config, today) }
            val platformCandidates = async { discoverPlatformDomesticCandidates(config, today.year) }
            val captures = capturedFeeds.await()
            val directShows =
                captures.flatMap { captured ->
                    captured.contents.flatMap { content ->
                        discoverCalendarShowsFromHtml(captured.feed, content, today.year)
                    }
                }.filter { show ->
                    show.sources.all { source -> runCatching { validateSource(source, config) }.isSuccess }
                }
            val tmdb = tmdbCandidates.await()
            val platform = platformCandidates.await()
            val candidates =
                if (config.domesticCandidates.enabled) {
                    mergeDomesticCandidates(tmdb + platform, config.domesticCandidates.maxShows)
                } else {
                    emptyList()
                }
            val evidenceMatched =
                captures
                    .filter { it.feed.type == "VerifiedAccount" }
                    .flatMap { captured ->
                        captured.contents.flatMap { content ->
                            discoverCandidateCalendarShowsFromHtml(
                                feed = captured.feed,
                                html = content,
                                candidates = candidates,
                                defaultYear = today.year,
                            )
                        }
                    }.filter { show ->
                        show.sources.all { source -> runCatching { validateSource(source, config) }.isSuccess }
                    }
            val shows = mergeIngestionShows(directShows + evidenceMatched).take(MAX_DISCOVERED_SHOWS)
            DomesticDiscoveryBatch(
                shows = shows,
                counts =
                    DomesticCandidateCounts(
                        tmdb = tmdb.size,
                        platform = platform.size,
                        merged = candidates.size,
                        evidenceMatched = evidenceMatched.distinctBy { normalizeTitle(it.title) }.size,
                    ),
            )
        }

    private suspend fun captureDiscoveryFeeds(config: CalendarIngestionConfig): List<CapturedCalendarDiscoveryFeed> =
        coroutineScope {
            config.discoveryFeeds.map { feed ->
                async {
                    discoveryRequests.withPermit {
                        val raw = fetchText(feed.url)
                        val rendered = config.pageRenderer?.let { fetchRenderedText(feed.url, it) }
                        CapturedCalendarDiscoveryFeed(
                            feed = feed,
                            contents = listOfNotNull(rendered, raw).distinct(),
                        )
                    }
                }
            }.awaitAll()
        }

    private fun discoverTmdbDomesticCandidates(
        config: CalendarIngestionConfig,
        today: LocalDate,
    ): List<DomesticShowCandidate> {
        val candidateConfig = config.domesticCandidates
        val tmdbConfig = candidateConfig.tmdbOnAir
        if (!candidateConfig.enabled || !tmdbConfig.enabled) return emptyList()
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return emptyList()
        val encodedLanguage = java.net.URLEncoder.encode(tmdbConfig.language, Charsets.UTF_8)
        return (1..tmdbConfig.maxPages).flatMap { page ->
            val separator = if ('?' in tmdbConfig.endpoint) '&' else '?'
            val url = "${tmdbConfig.endpoint}$separator" + "language=$encodedLanguage&page=$page"
            fetchBearerJson(url, token)
                ?.let { body -> DomesticCandidateParser.parseTmdbOnAir(body, today, tmdbConfig) }
                .orEmpty()
        }.distinctBy(DomesticShowCandidate::tmdbId)
            .sortedByDescending(DomesticShowCandidate::discoveryWeight)
            .take(tmdbConfig.maxShows)
    }

    private suspend fun discoverPlatformDomesticCandidates(
        config: CalendarIngestionConfig,
        defaultYear: Int,
    ): List<DomesticShowCandidate> {
        if (!config.domesticCandidates.enabled) return emptyList()
        return coroutineScope {
            config.domesticCandidates.platformCatalogs.map { feed ->
                async {
                    discoveryRequests.withPermit {
                        val raw = fetchText(feed.url)
                        val rendered =
                            if (feed.render) {
                                config.pageRenderer?.let { fetchRenderedText(feed.url, it) }
                            } else {
                                null
                            }
                        listOfNotNull(rendered, raw)
                            .distinct()
                            .flatMap { body -> DomesticCandidateParser.parsePlatformCatalog(body, feed, defaultYear) }
                            .distinctBy { normalizeTitle(it.title) }
                            .take(feed.maxShows)
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private fun discoverOverseasShows(
        config: CalendarIngestionConfig,
        today: LocalDate,
    ): List<CalendarIngestionShow> {
        if (!config.overseas.enabled) return emptyList()
        val body = fetchText(config.overseas.tvmazeFullScheduleUrl, MAX_TVMAZE_SCHEDULE_CHARS) ?: return emptyList()
        return OverseasScheduleParser.discoverTvmazeShows(body, today, config.overseas)
    }

    private suspend fun collectShowSchedule(
        show: CalendarIngestionShow,
        config: CalendarIngestionConfig,
        fallbackSchedules: List<CalendarSeries>,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? {
        val fallback =
            fallbackSchedules.firstOrNull { current ->
                current.tmdbId == show.tmdbId || normalizeTitle(current.title) == normalizeTitle(show.title)
            }
        val identity = resolveIdentity(show)
        if (identity == null) {
            logCalendarRejection(show, "identity")
            return fallback?.copy(revision = revision)
        }
        if (show.origin == "Foreign") {
            val compiled = collectOverseasSchedule(show, identity, revision, generatedAt)
            if (compiled == null) logCalendarRejection(show, "overseas-evidence-gate")
            return compiled ?: fallbackSchedules.firstOrNull { it.tmdbId == identity.tmdbId }?.copy(revision = revision)
        }
        val sources =
            coroutineScope {
                show.sources
                    .map { source ->
                        async {
                            sourceRequests.withPermit { captureSource(show, source, config) }
                        }
                    }.awaitAll()
                    .filterNotNull()
            }
        if (sources.isEmpty()) logCalendarRejection(show, "source-empty")
        val compiled = CalendarEvidenceGate.compile(show, identity, sources, revision, generatedAt)
        if (compiled == null) logCalendarRejection(show, "evidence-gate")
        return compiled ?: fallbackSchedules.firstOrNull { it.tmdbId == identity.tmdbId }?.copy(revision = revision)
    }

    private suspend fun collectOverseasSchedule(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        revision: String,
        generatedAt: String,
    ): CalendarSeries? =
        coroutineScope {
            val tmdb = async { sourceRequests.withPermit { fetchTmdbSeason(show, identity, generatedAt) } }
            val tvmaze = async { sourceRequests.withPermit { fetchTvmazeEpisodes(show, generatedAt) } }
            OverseasEvidenceGate.compile(
                show = show,
                identity = identity,
                sources = listOfNotNull(tmdb.await(), tvmaze.await()),
                revision = revision,
                generatedAt = generatedAt,
            )
        }

    private fun fetchTmdbSeason(
        show: CalendarIngestionShow,
        identity: ResolvedCalendarIdentity,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return null
        val url = "https://api.themoviedb.org/3/tv/${identity.tmdbId}/season/${show.seasonNumber}?language=en-US"
        val body = fetchBearerJson(url, token) ?: return null
        return OverseasScheduleParser.parseTmdbSeason(body, url, capturedAt)
    }

    private fun fetchTvmazeEpisodes(
        show: CalendarIngestionShow,
        capturedAt: String,
    ): StructuredCalendarSource? {
        val showId = show.tvmazeId ?: lookupTvmazeId(show.imdbId) ?: return null
        val url = "https://api.tvmaze.com/shows/$showId/episodes?specials=0"
        val body = fetchText(url) ?: return null
        return OverseasScheduleParser.parseTvmazeEpisodes(
            body = body,
            seasonNumber = show.seasonNumber,
            showId = showId,
            timeZoneId = show.timeZoneId,
            capturedAt = capturedAt,
        )
    }

    private fun lookupTvmazeId(imdbId: String?): Int? {
        val normalized = imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: return null
        val body = fetchText("https://api.tvmaze.com/lookup/shows?imdb=$normalized") ?: return null
        return OverseasScheduleParser.parseTvmazeShowId(body)
    }

    private suspend fun captureSource(
        show: CalendarIngestionShow,
        source: CalendarSourceConfig,
        config: CalendarIngestionConfig,
    ): ParsedCalendarSource? {
        validateSource(source, config)
        val capturedAt = Instant.now().toString()
        val rawHtml = fetchText(source.url)
        var html =
            rawHtml ?: fetchRenderedText(source.url, config.pageRenderer) ?: run {
                logCalendarRejection(show, "source-fetch")
                return null
            }
        var pageEpisodes = ChineseScheduleParser.parse(htmlToText(html), show.year, show.accessTier)
        var extractedImages = extractCalendarImages(source.url, html)
        if (
            pageEpisodes.isEmpty() &&
            extractedImages.isEmpty() &&
            source.imageUrls.isEmpty() &&
            rawHtml != null
        ) {
            fetchRenderedText(source.url, config.pageRenderer)?.let { rendered ->
                html = rendered
                pageEpisodes = ChineseScheduleParser.parse(htmlToText(rendered), show.year, show.accessTier)
                extractedImages = extractCalendarImages(source.url, rendered)
            }
        }
        val imageUrls =
            (if (source.imageUrls.isNotEmpty()) source.imageUrls else extractedImages)
                .distinct()
                .take(MAX_IMAGES_PER_SOURCE)
        val ocrCaptures = mutableListOf<OcrConsensusCapture>()
        imageUrls.forEach { imageUrl ->
            val readings =
                coroutineScope {
                    config.ocrProviders.take(MAX_OCR_PROVIDERS).map { provider ->
                        async {
                            ocrRequests.withPermit {
                                ocr(provider, imageUrl)?.let { text ->
                                    OcrReading(
                                        providerId = provider.id,
                                        text = text,
                                        episodes = ChineseScheduleParser.parse(text, show.year, show.accessTier),
                                    )
                                }
                            }
                        }
                    }.awaitAll()
                        .filterNotNull()
                }
            if (readings.size != MAX_OCR_PROVIDERS) {
                val successfulProviders =
                    readings.map(OcrReading::providerId)
                        .sorted()
                        .joinToString("+")
                        .replace(Regex("[^A-Za-z0-9._+-]"), "_")
                        .take(120)
                        .ifBlank { "none" }
                logCalendarRejection(show, "ocr-provider-count-${readings.size}-$successfulProviders")
                return@forEach
            }
            val resolution = OcrConfidenceGate.resolve(readings, show.year, show.accessTier)
            if (resolution.conflict) {
                logCalendarRejection(show, "ocr-conflict")
                return null
            }
            if (resolution.episodes.isEmpty() || resolution.agreement == OcrAgreement.None) {
                logCalendarRejection(show, "ocr-no-consensus")
                return@forEach
            }
            ocrCaptures +=
                OcrConsensusCapture(
                    imageUrl = imageUrl,
                    episodes = resolution.episodes,
                    readingHashes = readings.map { reading -> reading.providerId to reading.text.sha256() },
                    agreement = resolution.agreement,
                )
        }
        val ocrConsensus = ocrCaptures.isNotEmpty()
        val combined = mergeWithoutConflict(listOf(pageEpisodes) + ocrCaptures.map(OcrConsensusCapture::episodes))
        if (combined == null) {
            logCalendarRejection(show, "source-ocr-conflict")
            return null
        }
        if (combined.isEmpty()) {
            logCalendarRejection(show, "coordinates-empty")
            return null
        }
        val evidenceFingerprint =
            buildString {
                append("official-page-v1:")
                append(html.sha256())
                ocrCaptures.sortedBy(OcrConsensusCapture::imageUrl).forEach { capture ->
                    append('|')
                    append(capture.imageUrl)
                    capture.readingHashes.sortedBy { it.first }.forEach { (providerId, hash) ->
                        append('|')
                        append(providerId)
                        append(':')
                        append(hash)
                    }
                }
            }.sha256()
        return ParsedCalendarSource(
            source = source,
            capturedAt = capturedAt,
            contentHash = evidenceFingerprint,
            episodes = combined,
            ocrConsensus = ocrConsensus,
            ocrAgreement = ocrCaptures.maxByOrNull { ocrConfidenceBonus(it.agreement) }?.agreement ?: OcrAgreement.None,
        )
    }

    private fun resolveIdentity(show: CalendarIngestionShow): ResolvedCalendarIdentity? {
        show.tmdbId?.takeIf { it > 0 }?.let { id ->
            val url = "https://www.themoviedb.org/tv/$id"
            return ResolvedCalendarIdentity(
                id,
                show.title,
                show.posterPath,
                url,
                "$id:${show.title}:${show.year}".sha256(),
            )
        }
        val token = tmdbToken?.takeIf(String::isNotBlank) ?: return null
        if (show.origin == "Foreign" && !show.imdbId.isNullOrBlank()) {
            resolveTmdbIdentityByImdb(show, token)?.let { return it }
        }
        val encodedTitle = java.net.URLEncoder.encode(show.title, Charsets.UTF_8)
        val language = if (show.origin == "Foreign") "en-US" else "zh-CN"
        val url = "https://api.themoviedb.org/3/search/tv?language=$language&query=$encodedTitle&year=${show.year}"
        val body = fetchBearerJson(url, token) ?: return null
        val candidates = ingestionJson.parseToJsonElement(body).jsonObject["results"]?.jsonArray.orEmpty()
        val normalized = normalizeTitle(show.title)
        val exact =
            candidates.mapNotNull { node ->
                val obj = node.jsonObject
                val title = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val original = obj["original_name"]?.jsonPrimitive?.content.orEmpty()
                val date = obj["first_air_date"]?.jsonPrimitive?.content.orEmpty()
                val year = date.take(4).toIntOrNull()
                if (normalizeTitle(title) != normalized && normalizeTitle(original) != normalized) {
                    return@mapNotNull null
                }
                if (year != null && (year - show.year).absoluteValue > 1) return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@mapNotNull null
                val poster = obj["poster_path"]?.jsonPrimitive?.content
                ResolvedCalendarIdentity(
                    id,
                    title,
                    poster,
                    "https://www.themoviedb.org/tv/$id",
                    body.sha256(),
                )
            }
        return exact.singleOrNull()
    }

    private fun resolveTmdbIdentityByImdb(
        show: CalendarIngestionShow,
        token: String,
    ): ResolvedCalendarIdentity? {
        val imdbId = show.imdbId?.takeIf { it.matches(Regex("tt\\d{5,12}")) } ?: return null
        val url = "https://api.themoviedb.org/3/find/$imdbId?external_source=imdb_id&language=en-US"
        val body = fetchBearerJson(url, token) ?: return null
        val matches = ingestionJson.parseToJsonElement(body).jsonObject["tv_results"]?.jsonArray.orEmpty()
        val match = matches.singleOrNull()?.jsonObject ?: return null
        val id = match["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        return ResolvedCalendarIdentity(
            tmdbId = id,
            title = match["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: show.title,
            posterPath = match["poster_path"]?.jsonPrimitive?.content,
            evidenceUrl = "https://www.themoviedb.org/tv/$id",
            evidenceHash = body.sha256(),
        )
    }

    private fun fetchBearerJson(
        url: String,
        token: String,
    ): String? {
        val request =
            HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull() ?: return null
        return response.body().takeIf { response.statusCode() in 200..299 && it.length <= MAX_SOURCE_CHARS }
    }

    private fun logCalendarRejection(
        show: CalendarIngestionShow,
        stage: String,
    ) {
        val safeTitle = show.title.replace(Regex("[\\r\\n\\t]"), " ").take(120)
        System.err.println("calendar ingestion rejected title=$safeTitle stage=$stage")
    }

    private suspend fun ocr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
    ): String? {
        val key = provider.apiKeyEnvironment?.let(System::getenv)
        if (provider.apiKeyEnvironment != null && key.isNullOrBlank()) return null
        return when (provider.protocol) {
            OCR_PROTOCOL_BRIDGE -> bridgeOcr(provider, imageUrl, key)
            OCR_PROTOCOL_PADDLE_JOBS ->
                paddleRequests.withPermit {
                    paddleOcr(provider, imageUrl, key ?: return@withPermit null)
                }
            OCR_PROTOCOL_OCR_SPACE ->
                ocrSpaceRequests.withPermit {
                    ocrSpace(provider, imageUrl, key ?: return@withPermit null)
                }
            else -> null
        }
    }

    private fun bridgeOcr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String?,
    ): String? {
        val payload = "{\"imageUrl\":${ingestionJson.encodeToString(imageUrl)}}"
        val builder =
            HttpRequest.newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (!key.isNullOrBlank()) builder.header("Authorization", "Bearer $key")
        val response =
            runCatching { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }
                .getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299) return null
        return runCatching {
            ingestionJson.parseToJsonElement(response.body()).jsonObject["text"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun paddleOcr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String,
    ): String? {
        val model = provider.model ?: DEFAULT_PADDLE_OCR_MODEL
        val optionalPayload =
            buildJsonObject {
                put("useDocOrientationClassify", false)
                put("useDocUnwarping", false)
                when (model) {
                    PADDLE_OCR_V6_MODEL -> put("useTextlineOrientation", false)
                    else -> put("useChartRecognition", false)
                }
            }
        val payload =
            ingestionJson.encodeToString(
                PaddleOcrSubmitRequest(
                    fileUrl = imageUrl,
                    model = model,
                    optionalPayload = optionalPayload,
                ),
            )
        val submitRequest =
            HttpRequest.newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        var submitResponse: HttpResponse<String>? = null
        for (attempt in 0 until PADDLE_SUBMIT_ATTEMPTS) {
            val response = runCatching { http.send(submitRequest, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            if (response != null && response.statusCode() in 200..299) {
                submitResponse = response
                break
            }
            val retryable =
                response == null ||
                    response.statusCode() == 429 ||
                    response.statusCode() >= 500 ||
                    (response.statusCode() == 400 && PaddleOcrResponseParser.isSubmitQueueFull(response.body()))
            if (!retryable || attempt == PADDLE_SUBMIT_ATTEMPTS - 1) return null
            if (!sleepForPaddlePoll(PADDLE_SUBMIT_RETRY_MILLIS * (attempt + 1L))) return null
        }
        val acceptedResponse = submitResponse ?: return null
        val jobId = PaddleOcrResponseParser.submittedJobId(acceptedResponse.body()) ?: return null
        val pollUri = URI(provider.endpoint.trimEnd('/') + "/" + java.net.URLEncoder.encode(jobId, Charsets.UTF_8))
        val deadline = System.nanoTime() + Duration.ofSeconds(provider.pollTimeoutSeconds).toNanos()

        while (System.nanoTime() < deadline) {
            if (!sleepForPaddlePoll(provider.pollIntervalMillis)) return null
            val pollRequest =
                HttpRequest.newBuilder(pollUri)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer $key")
                    .GET()
                    .build()
            val pollResponse =
                runCatching { http.send(pollRequest, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                    ?: continue
            if (pollResponse.statusCode() !in 200..299) return null
            val snapshot = PaddleOcrResponseParser.jobSnapshot(pollResponse.body()) ?: return null
            when (snapshot.state) {
                "pending", "running" -> Unit
                "done" -> return snapshot.resultUrl?.let(::downloadPaddleResult)
                "failed" -> return null
                else -> return null
            }
        }
        return null
    }

    private fun ocrSpace(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
        key: String,
    ): String? {
        val form =
            listOf(
                "url" to imageUrl,
                "language" to (provider.language ?: DEFAULT_OCR_SPACE_LANGUAGE),
                "isOverlayRequired" to "false",
                "detectOrientation" to "true",
                "scale" to "true",
                "isTable" to "true",
                "OCREngine" to (provider.engine ?: DEFAULT_OCR_SPACE_ENGINE).toString(),
            ).joinToString("&") { (name, value) ->
                java.net.URLEncoder.encode(name, Charsets.UTF_8) +
                    "=" +
                    java.net.URLEncoder.encode(value, Charsets.UTF_8)
            }
        val request =
            HttpRequest.newBuilder(URI(provider.endpoint))
                .timeout(Duration.ofSeconds(provider.pollTimeoutSeconds))
                .header("apikey", key)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        for (attempt in 0 until OCR_SPACE_ATTEMPTS) {
            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            if (
                response != null &&
                response.statusCode() in 200..299 &&
                response.body().length <= MAX_OCR_RESULT_CHARS
            ) {
                OcrSpaceResponseParser.extractText(response.body())?.let { return it }
                System.err.println("calendar ingestion OCR.space empty ${OcrSpaceResponseParser.failureSummary(response.body())}")
            } else {
                System.err.println(
                    "calendar ingestion OCR.space request-failed status=${response?.statusCode() ?: -1} " +
                        "bytes=${response?.body()?.length ?: 0}",
                )
            }
            val retryable =
                response == null ||
                    response.statusCode() == 429 ||
                    response.statusCode() >= 500 ||
                    response.statusCode() in 200..299
            if (!retryable || attempt == OCR_SPACE_ATTEMPTS - 1) return null
            if (!sleepForPaddlePoll(OCR_SPACE_RETRY_MILLIS * (attempt + 1L))) return null
        }
        return null
    }

    private fun sleepForPaddlePoll(milliseconds: Long): Boolean =
        try {
            Thread.sleep(milliseconds)
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

    private fun downloadPaddleResult(url: String): String? {
        val uri = runCatching { requireHttps(url) }.getOrNull() ?: return null
        val request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
        val response =
            runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299 || response.body().length > MAX_OCR_RESULT_CHARS) return null
        return PaddleOcrResponseParser.extractMarkdownText(response.body())
    }

    private fun fetchText(
        url: String,
        maxChars: Int = MAX_SOURCE_CHARS,
    ): String? {
        val uri = runCatching { requireHttps(url) }.getOrNull() ?: return null
        repeat(SOURCE_FETCH_ATTEMPTS) { attempt ->
            val request =
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "YfuseCalendarBot/1.1 (+official-schedule-evidence)")
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build()
            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            if (
                response != null &&
                response.statusCode() in 200..299 &&
                response.body().length <= maxChars
            ) {
                return response.body()
            }
            val retryable = response == null || response.statusCode() == 429 || response.statusCode() >= 500
            if (!retryable || attempt == SOURCE_FETCH_ATTEMPTS - 1) return null
            try {
                Thread.sleep(SOURCE_FETCH_RETRY_BASE_MS * (attempt + 1L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun fetchRenderedText(
        url: String,
        renderer: CalendarPageRendererConfig?,
    ): String? {
        renderer ?: return null
        val endpoint = runCatching { validateCalendarRendererEndpoint(renderer.endpoint) }.getOrNull() ?: return null
        val key = renderer.apiKeyEnvironment?.let(System::getenv)
        if (renderer.apiKeyEnvironment != null && key.isNullOrBlank()) return null
        val payload = "{\"url\":${ingestionJson.encodeToString(url)}}"
        val builder =
            HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
        if (!key.isNullOrBlank()) builder.header("Authorization", "Bearer $key")
        val response =
            runCatching { http.send(builder.build(), HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299 || response.body().length > MAX_SOURCE_CHARS) return null
        return runCatching {
            val root = ingestionJson.parseToJsonElement(response.body()).jsonObject
            (root["html"] ?: root["content"])
                ?.jsonPrimitive
                ?.content
                ?.takeIf { it.length <= MAX_SOURCE_CHARS }
        }.getOrNull()
    }

    private fun validateConfig(config: CalendarIngestionConfig) {
        require(config.refreshMinutes in 15..1_440)
        require(config.shows.size <= 1_000)
        require(config.discoveryFeeds.size <= MAX_DISCOVERY_FEEDS)
        val domestic = config.domesticCandidates
        require(domestic.maxShows in 1..MAX_DOMESTIC_CANDIDATES)
        require(domestic.platformCatalogs.size <= MAX_PLATFORM_CATALOGS)
        require(domestic.tmdbOnAir.maxPages in 1..10)
        require(domestic.tmdbOnAir.maxShows in 1..MAX_DOMESTIC_CANDIDATES)
        require(domestic.tmdbOnAir.language.matches(Regex("[a-z]{2}(?:-[A-Z]{2})?")))
        require(domestic.tmdbOnAir.originCountries.size <= 10)
        require(domestic.tmdbOnAir.originCountries.all { it.matches(Regex("[A-Za-z]{2}")) })
        require(domestic.tmdbOnAir.originalLanguages.size <= 10)
        require(domestic.tmdbOnAir.originalLanguages.all { it.matches(Regex("[A-Za-z]{2,3}")) })
        require(domestic.tmdbOnAir.requiredGenreIds.size <= 20)
        require(domestic.tmdbOnAir.requiredGenreIds.all { it > 0 })
        if (domestic.enabled && domestic.tmdbOnAir.enabled) {
            require(!tmdbToken.isNullOrBlank()) { "TMDB token is required for domestic candidate discovery" }
            val tmdbUri = requireHttps(domestic.tmdbOnAir.endpoint)
            require(tmdbUri.host.equals("api.themoviedb.org", true))
            require(tmdbUri.path == "/3/tv/on_the_air")
        }
        domestic.platformCatalogs.forEach { feed ->
            require(feed.publisher.isNotBlank() && feed.publisher.length <= 80)
            require(feed.platform in PLATFORM_HOSTS)
            require(feed.maxShows in 1..100)
            require(feed.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
            feed.titlePattern?.let { pattern ->
                require(pattern.length in 1..1_000)
                require(runCatching { Regex(pattern) }.isSuccess)
            }
            val uri = requireHttps(feed.url)
            require(PLATFORM_HOSTS.getValue(feed.platform).any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) })
        }
        require(config.overseas.pastDays in 0..30)
        require(config.overseas.futureDays in 1..180)
        require(config.overseas.maxShows in 1..500)
        require(config.overseas.countryCodes.size <= 30)
        require(config.overseas.countryCodes.all { it.matches(Regex("[A-Za-z]{2}")) })
        require(config.overseas.allowedShowTypes.isNotEmpty() && config.overseas.allowedShowTypes.size <= 10)
        require(config.overseas.allowedShowTypes.all { it in TVMAZE_SHOW_TYPES })
        if (config.overseas.enabled) {
            require(!tmdbToken.isNullOrBlank()) { "TMDB token is required for overseas identity mapping" }
            val overseasUri = requireHttps(config.overseas.tvmazeFullScheduleUrl)
            require(overseasUri.host.equals("api.tvmaze.com", true))
        }
        validateOcrProviders(config.ocrProviders)
        config.pageRenderer?.let { renderer ->
            validateCalendarRendererEndpoint(renderer.endpoint)
            require(renderer.apiKeyEnvironment?.length?.let { it in 1..120 } != false)
        }
        require(
            config.verifiedAccounts.map(VerifiedCalendarAccount::publisherId).distinct().size ==
                config.verifiedAccounts.size,
        )
        config.verifiedAccounts.forEach { account ->
            require(account.publisherId.isNotBlank() && account.publisher.isNotBlank())
            val uri = requireHttps(account.profileUrl)
            require(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))
            require(account.profileUrl.contains(account.publisherId))
        }
        config.shows.forEach { show ->
            validateShowDefaults(show)
            require(show.sources.size <= MAX_SOURCES_PER_SHOW)
            require(show.origin == "Foreign" || show.sources.isNotEmpty())
        }
        config.discoveryFeeds.forEach { feed ->
            require(feed.publisher.isNotBlank() && feed.publisher.length <= 80)
            require(feed.titlePattern.length in 1..1_000)
            require(runCatching { Regex(feed.titlePattern) }.isSuccess)
            require(feed.maxShows in 1..100)
            validateShowDefaults(
                CalendarIngestionShow(
                    title = "discovered",
                    year = feed.year ?: LocalDate.now(ZoneOffset.UTC).year,
                    seasonNumber = feed.seasonNumber,
                    airTime = feed.airTime,
                    timeZoneId = feed.timeZoneId,
                    platforms = feed.platforms,
                    accessTier = feed.accessTier,
                    sources = emptyList(),
                ),
            )
            validateSource(
                CalendarSourceConfig(
                    type = feed.type,
                    platform = feed.platform,
                    publisherId = feed.publisherId,
                    publisher = feed.publisher,
                    url = feed.url,
                ),
                config,
            )
        }
    }

    private fun validateShowDefaults(show: CalendarIngestionShow) {
        require(show.title.isNotBlank() && show.title.length <= 120)
        require(show.year in 1900..2100)
        require(show.seasonNumber in 1..100)
        require((show.airTime == null) == (show.timeZoneId == null))
        show.airTime?.let { require(runCatching { LocalTime.parse(it) }.isSuccess) }
        show.timeZoneId?.let { require(runCatching { ZoneId.of(it) }.isSuccess) }
        require(show.platforms.isNotEmpty() && show.platforms.size <= 10)
        require(show.platforms.all { it.isNotBlank() && it.length <= 40 })
        require(show.origin in setOf("Domestic", "Foreign"))
        if (show.origin == "Domestic") require(show.platforms.all(PLATFORM_HOSTS::containsKey))
        require(show.availabilityRegion?.matches(Regex("[A-Z]{2}|GLOBAL")) != false)
        require(show.releaseMode in setOf("Scheduled", "Weekly", "Batch", "DateOnly", "Unknown"))
        require(show.discoveryWeight in 0..1_000)
        require(show.tvmazeId?.let { it > 0 } != false)
        require(show.imdbId?.matches(Regex("tt\\d{5,12}")) != false)
        require(show.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
    }

    private fun validateSource(
        source: CalendarSourceConfig,
        config: CalendarIngestionConfig,
    ) {
        require(source.type in setOf("PlatformPage", "VerifiedAccount"))
        val uri = requireHttps(source.url)
        if (source.type == "PlatformPage") {
            val allowed = PLATFORM_HOSTS[source.platform] ?: error("Unsupported calendar platform")
            require(allowed.any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) })
        } else {
            require(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))
            val account = config.verifiedAccounts.singleOrNull { it.publisherId == source.publisherId }
            requireNotNull(account) { "Official account is not allowlisted" }
            require(account.publisher == source.publisher)
            require(source.url.contains(account.publisherId))
        }
        source.imageUrls.forEach(::requireHttps)
    }

    private fun readExistingPublication(): CalendarPublication? =
        scheduleStore.current()
            ?: outputFile?.takeIf(File::isFile)?.let { file ->
                runCatching {
                    ingestionJson.decodeFromString<CalendarPublication>(file.readText())
                }.getOrNull()
            }

    private fun writeAtomically(publication: CalendarPublication) {
        val target = outputFile ?: return
        target.absoluteFile.parentFile?.mkdirs()
        val temporary = File(target.absolutePath + ".tmp")
        temporary.writeText(ingestionJson.encodeToString(publication))
        runCatching {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun CoroutineScope.launchCalendarIngestionFromEnvironment(
    scheduleStore: CalendarScheduleStore = NoOpCalendarScheduleStore,
): Job? {
    val configPath =
        System.getenv("YFUSE_CALENDAR_INGEST_CONFIG")
            ?.takeIf(String::isNotBlank)
            ?: return null
    val outputFile =
        System.getenv("YFUSE_CALENDAR_SCHEDULES_PATH")
            ?.takeIf(String::isNotBlank)
            ?.let(::File)
    if (outputFile == null && scheduleStore === NoOpCalendarScheduleStore) return null
    val runtime =
        CalendarIngestionRuntime(
            configFile = File(configPath),
            outputFile = outputFile,
            tmdbToken = System.getenv("TMDB_TOKEN"),
            scheduleStore = scheduleStore,
        )
    return launch {
        while (isActive) {
            runCatching { runtime.runOnce() }
                .onFailure { failure ->
                    CalendarIngestionHealth.failed(failure)
                    System.err.println("calendar ingestion failed: ${failure.message}")
                }
            val minutes =
                runCatching {
                    ingestionJson.decodeFromString<CalendarIngestionConfig>(File(configPath).readText()).refreshMinutes
                }.getOrDefault(30)
            delay(minutes.coerceIn(15, 1_440) * 60_000L)
        }
    }
}

private fun mergeWithoutConflict(parts: List<Map<Int, String>>): Map<Int, String>? {
    val merged = linkedMapOf<Int, String>()
    parts.forEach { part ->
        part.forEach { (episode, date) ->
            val previous = merged.putIfAbsent(episode, date)
            if (previous != null && previous != date) return null
        }
    }
    return merged
}

private fun ocrConfidenceBonus(agreement: OcrAgreement): Int =
    when (agreement) {
        OcrAgreement.Exact -> 20
        OcrAgreement.PartialSubset -> 15
        OcrAgreement.CoordinateIntersection -> 10
        OcrAgreement.SemanticCorroboration -> 15
        OcrAgreement.None -> 0
    }

private fun htmlToText(html: String): String =
    html.replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.takeIf { it in Char.MIN_VALUE.code..Char.MAX_VALUE.code }?.toChar()?.toString()
                ?: match.value
        }
        .replace(Regex("&#x([0-9a-fA-F]+);")) { match ->
            match.groupValues[1].toIntOrNull(16)?.takeIf { it in Char.MIN_VALUE.code..Char.MAX_VALUE.code }?.toChar()?.toString()
                ?: match.value
        }

private data class CalendarArchiveAnchor(
    val start: Int,
    val end: Int,
    val url: String,
    val label: String,
)

private data class CalendarArchiveCandidate(
    val title: String,
    val sourceUrl: String,
    val imageUrls: List<String> = emptyList(),
    val priority: Int = 0,
)

internal fun discoverCalendarShowsFromHtml(
    feed: CalendarDiscoveryFeed,
    html: String,
    defaultYear: Int,
): List<CalendarIngestionShow> {
    val titleRegex = runCatching { Regex(feed.titlePattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
    val base = runCatching { URI(feed.url) }.getOrNull() ?: return emptyList()
    val anchors = extractCalendarArchiveAnchors(base, html)
    val candidates = mutableListOf<CalendarArchiveCandidate>()

    anchors.forEach { anchor ->
        val title = extractCalendarArchiveTitle(anchor.label, titleRegex) ?: return@forEach
        if (!isUsableCalendarSource(feed, anchor.url)) return@forEach
        candidates += CalendarArchiveCandidate(title, anchor.url, priority = 20)
    }

    // On verified-account timelines the permanent post link, body and images are separate DOM
    // nodes. Segmenting from one permanent link to the next keeps them associated without
    // depending on Weibo's frequently-changing CSS class names.
    val permalinks =
        if (feed.type == "VerifiedAccount") {
            anchors
                .filter { isUsableCalendarSource(feed, it.url) }
                .distinctBy(CalendarArchiveAnchor::url)
        } else {
            emptyList()
        }
    permalinks.forEachIndexed { index, anchor ->
        val end = permalinks.getOrNull(index + 1)?.start ?: html.length
        if (end <= anchor.start || end - anchor.start > MAX_ARCHIVE_ENTRY_CHARS) return@forEachIndexed
        val entryHtml = html.substring(anchor.start, end)
        val title = extractCalendarArchiveTitle(htmlToText(entryHtml), titleRegex) ?: return@forEachIndexed
        candidates +=
            CalendarArchiveCandidate(
                title = title,
                sourceUrl = anchor.url,
                imageUrls = extractArchiveEntryImages(base, entryHtml),
                priority = 100,
            )
    }

    discoverCalendarArchiveJson(feed, html, titleRegex, base).forEach(candidates::add)

    return candidates
        .asSequence()
        .filter { it.title.length in 1..120 }
        .sortedWith(
            compareByDescending<CalendarArchiveCandidate> { it.priority }
                .thenByDescending { it.imageUrls.size },
        ).distinctBy { normalizeTitle(it.title) to (feed.year ?: defaultYear) }
        .take(feed.maxShows)
        .map { candidate ->
            CalendarIngestionShow(
                title = candidate.title,
                year = feed.year?.takeIf { it in 1900..2100 } ?: defaultYear,
                seasonNumber = feed.seasonNumber,
                airTime = feed.airTime,
                timeZoneId = feed.timeZoneId,
                platforms = feed.platforms,
                accessTier = feed.accessTier,
                sources =
                    listOf(
                        CalendarSourceConfig(
                            type = feed.type,
                            platform = feed.platform,
                            publisherId = feed.publisherId,
                            publisher = feed.publisher,
                            url = candidate.sourceUrl,
                            imageUrls = candidate.imageUrls,
                        ),
                    ),
            )
        }
        .toList()
}

/**
 * Uses a broad candidate set to recover official posts whose wording does not fit the generic
 * title pattern. A candidate still needs one unambiguous mention in an allowlisted account post
 * plus a schedule/update signal; the returned source then goes through the normal dual-OCR gate.
 */
internal fun discoverCandidateCalendarShowsFromHtml(
    feed: CalendarDiscoveryFeed,
    html: String,
    candidates: List<DomesticShowCandidate>,
    defaultYear: Int,
): List<CalendarIngestionShow> {
    if (feed.type != "VerifiedAccount" || candidates.isEmpty()) return emptyList()
    val base = runCatching { URI(feed.url) }.getOrNull() ?: return emptyList()
    val matches = mutableListOf<Pair<DomesticShowCandidate, CalendarArchiveCandidate>>()

    fun addEvidence(
        text: String,
        sourceUrl: String,
        imageUrls: List<String>,
        priority: Int,
    ) {
        if (!CALENDAR_CANDIDATE_EVIDENCE_KEYWORDS.containsMatchIn(text)) return
        if (!isUsableCalendarSource(feed, sourceUrl)) return
        val candidate = matchDomesticCandidate(text, candidates) ?: return
        matches += candidate to CalendarArchiveCandidate(candidate.title, sourceUrl, imageUrls, priority)
    }

    val anchors = extractCalendarArchiveAnchors(base, html)
    val permalinks =
        anchors
            .filter { isUsableCalendarSource(feed, it.url) }
            .distinctBy(CalendarArchiveAnchor::url)
    permalinks.forEachIndexed { index, anchor ->
        val end = permalinks.getOrNull(index + 1)?.start ?: html.length
        if (end <= anchor.start || end - anchor.start > MAX_ARCHIVE_ENTRY_CHARS) return@forEachIndexed
        val entryHtml = html.substring(anchor.start, end)
        addEvidence(
            text = htmlToText(entryHtml),
            sourceUrl = anchor.url,
            imageUrls = extractArchiveEntryImages(base, entryHtml),
            priority = 110,
        )
    }

    val root = runCatching { ingestionJson.parseToJsonElement(html) }.getOrNull()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val strings =
                    element.mapValues { (_, value) -> runCatching { value.jsonPrimitive.content }.getOrNull() }
                val text = ARCHIVE_JSON_TEXT_KEYS.mapNotNull(strings::get).joinToString(" ")
                val directUrl =
                    ARCHIVE_JSON_LINK_KEYS
                        .mapNotNull(strings::get)
                        .mapNotNull { resolveHttpsUrl(base, it) }
                        .firstOrNull { isUsableCalendarSource(feed, it) }
                val postId = ARCHIVE_JSON_POST_ID_KEYS.mapNotNull(strings::get).firstOrNull()
                val constructedUrl =
                    feed.publisherId
                        ?.takeIf { it.all(Char::isDigit) }
                        ?.let { publisherId -> postId?.let { "https://weibo.com/$publisherId/$it" } }
                        ?.takeIf { isUsableCalendarSource(feed, it) }
                (directUrl ?: constructedUrl)?.let { sourceUrl ->
                    addEvidence(
                        text = text,
                        sourceUrl = sourceUrl,
                        imageUrls = collectJsonImageUrls(element, base),
                        priority = 100,
                    )
                }
                element.values.forEach(::visit)
            }
            else -> runCatching { element.jsonArray }.getOrNull()?.forEach(::visit)
        }
    }
    root?.let(::visit)

    return matches
        .sortedWith(
            compareByDescending<Pair<DomesticShowCandidate, CalendarArchiveCandidate>> { it.second.priority }
                .thenByDescending { it.second.imageUrls.size },
        ).distinctBy { (candidate, archive) -> normalizeTitle(candidate.title) to archive.sourceUrl }
        .take(feed.maxShows)
        .map { (candidate, archive) ->
            CalendarIngestionShow(
                title = candidate.title,
                year = candidate.year.takeIf { it in 1900..2100 } ?: defaultYear,
                tmdbId = candidate.tmdbId,
                posterPath = candidate.posterPath,
                seasonNumber = feed.seasonNumber,
                airTime = feed.airTime,
                timeZoneId = feed.timeZoneId,
                platforms = (feed.platforms + candidate.platforms).distinct(),
                accessTier = candidate.accessTier.takeIf { it != "Unknown" } ?: feed.accessTier,
                discoveryWeight = candidate.discoveryWeight,
                sources =
                    listOf(
                        CalendarSourceConfig(
                            type = feed.type,
                            platform = feed.platform,
                            publisherId = feed.publisherId,
                            publisher = feed.publisher,
                            url = archive.sourceUrl,
                            imageUrls = archive.imageUrls,
                        ),
                    ),
            )
        }
}

private fun matchDomesticCandidate(
    text: String,
    candidates: List<DomesticShowCandidate>,
): DomesticShowCandidate? {
    val compactText = text.replace(Regex("\\s+"), " ").take(MAX_CANDIDATE_EVIDENCE_TEXT_CHARS)
    val normalizedText = normalizeTitle(compactText)
    val matching =
        candidates.filter { candidate ->
            (candidate.aliases + candidate.title).distinctBy(::normalizeTitle).any { alias ->
                val cleaned = alias.trim()
                if (cleaned.isBlank()) return@any false
                val escaped = Regex.escape(cleaned)
                val explicitlyNamed =
                    Regex("(?:#|《|「|『)\\s*$escaped\\s*(?:#|》|」|』)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(compactText)
                val normalizedAlias = normalizeTitle(cleaned)
                explicitlyNamed || (normalizedAlias.length >= 4 && normalizedText.contains(normalizedAlias))
            }
        }.distinctBy { candidate -> candidate.tmdbId?.let { "tmdb:$it" } ?: normalizeTitle(candidate.title) }
    return matching.singleOrNull()
}

private fun extractCalendarArchiveAnchors(
    base: URI,
    html: String,
): List<CalendarArchiveAnchor> =
    ARCHIVE_ANCHOR_REGEX.findAll(html).mapNotNull { anchor ->
        val attributes = anchor.groupValues[1] + " " + anchor.groupValues[3]
        val label =
            buildString {
                append(htmlToText(anchor.groupValues[4]))
                Regex("(?is)(?:title|aria-label)=[\"']([^\"']+)[\"']")
                    .find(attributes)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let {
                        append(' ')
                        append(htmlToText(it))
                    }
            }.replace(Regex("\\s+"), " ").trim()
        val sourceUrl = resolveHttpsUrl(base, anchor.groupValues[2]) ?: return@mapNotNull null
        CalendarArchiveAnchor(anchor.range.first, anchor.range.last + 1, sourceUrl, label)
    }.toList()

private fun extractCalendarArchiveTitle(
    sourceText: String,
    configuredPattern: Regex,
): String? {
    val text = sourceText.replace(Regex("\\s+"), " ").trim()
    configuredPattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanCalendarTitle)?.let { title ->
        if (title.isNotBlank()) return title
    }
    CALENDAR_HASHTAG_TITLE_PATTERNS.forEach { pattern ->
        pattern.find(text)?.groupValues?.getOrNull(1)?.let(::cleanCalendarTitle)?.let { title ->
            if (title.isNotBlank()) return title
        }
    }
    if (!CALENDAR_DISCOVERY_KEYWORDS.containsMatchIn(text)) return null
    Regex("[《「『]([^》」』]{1,120})[》」』]")
        .find(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::cleanCalendarTitle)
        ?.takeIf(String::isNotBlank)
        ?.let { return it }
    return Regex("#([^#]{1,100})#")
        .findAll(text)
        .map { it.groupValues[1] }
        .map(::cleanCalendarTitle)
        .filter { it.length in 1..60 && !GENERIC_CALENDAR_HASHTAGS.contains(it) }
        .minByOrNull(String::length)
}

private fun cleanCalendarTitle(value: String): String =
    value
        .replace(Regex("^(?:电视剧|剧集|网剧|网络剧|动画|综艺)"), "")
        .replace(
            Regex(
                "(?:最新)?(?:追剧|播出|更新|会员|加更|观看|暗恋)日历$|" +
                    "(?:大结局)?点映礼$|观看指引$|通关攻略$|今日开播$|开播$|定档(?:\\d{4})?$",
            ),
            "",
        ).trim(' ', '#', '《', '》', '「', '」', '『', '』', ':', '：', '-', '—')

private fun isUsableCalendarSource(
    feed: CalendarDiscoveryFeed,
    url: String,
): Boolean {
    val uri = runCatching { requireHttps(url) }.getOrNull() ?: return false
    if (feed.type == "VerifiedAccount") {
        if (!(uri.host.equals("weibo.com", true) || uri.host.endsWith(".weibo.com", true))) return false
        val publisherId = feed.publisherId ?: return false
        if (!url.contains(publisherId)) return false
        return WEIBO_PERMALINK_PATH.matches(uri.path)
    }
    val allowedHosts = PLATFORM_HOSTS[feed.platform] ?: return false
    if (Regex("\\.(?:jpe?g|png|webp|gif|svg)(?:$|[?#])", RegexOption.IGNORE_CASE).containsMatchIn(uri.path)) return false
    return url != feed.url && allowedHosts.any { uri.host.equals(it, true) || uri.host.endsWith(".$it", true) }
}

private fun extractArchiveEntryImages(
    base: URI,
    html: String,
): List<String> =
    ARCHIVE_IMAGE_REGEX.findAll(html)
        .mapNotNull { match ->
            val raw = match.groupValues[1].substringBefore(',').trim().substringBefore(' ')
            resolveHttpsUrl(base, raw)?.takeIf(::isLikelyCalendarMediaUrl)
        }.distinct()
        .take(MAX_IMAGES_PER_SOURCE)
        .toList()

private fun discoverCalendarArchiveJson(
    feed: CalendarDiscoveryFeed,
    content: String,
    titleRegex: Regex,
    base: URI,
): List<CalendarArchiveCandidate> {
    val root = runCatching { ingestionJson.parseToJsonElement(content) }.getOrNull() ?: return emptyList()
    val candidates = mutableListOf<CalendarArchiveCandidate>()
    fun visit(element: JsonElement) {
        when (element) {
            is JsonObject -> {
                val strings =
                    element.mapValues { (_, value) ->
                        runCatching { value.jsonPrimitive.content }.getOrNull()
                    }
                val text =
                    ARCHIVE_JSON_TEXT_KEYS
                        .mapNotNull(strings::get)
                        .joinToString(" ")
                val title = extractCalendarArchiveTitle(text, titleRegex)
                if (title != null) {
                    val directUrl =
                        ARCHIVE_JSON_LINK_KEYS
                            .mapNotNull(strings::get)
                            .mapNotNull { resolveHttpsUrl(base, it) }
                            .firstOrNull { isUsableCalendarSource(feed, it) }
                    val postId = ARCHIVE_JSON_POST_ID_KEYS.mapNotNull(strings::get).firstOrNull()
                    val constructedUrl =
                        feed.publisherId
                            ?.takeIf { it.all(Char::isDigit) }
                            ?.let { publisherId -> postId?.let { "https://weibo.com/$publisherId/$it" } }
                            ?.takeIf { isUsableCalendarSource(feed, it) }
                    val sourceUrl = directUrl ?: constructedUrl
                    if (sourceUrl != null) {
                        candidates +=
                            CalendarArchiveCandidate(
                                title = title,
                                sourceUrl = sourceUrl,
                                imageUrls = collectJsonImageUrls(element, base),
                                priority = 90,
                            )
                    }
                }
                element.values.forEach(::visit)
            }
            else -> runCatching { element.jsonArray }.getOrNull()?.forEach(::visit)
        }
    }
    visit(root)
    return candidates
}

private fun collectJsonImageUrls(
    element: JsonElement,
    base: URI,
): List<String> {
    data class RankedImageUrl(
        val url: String,
        val score: Int,
        val order: Int,
    )

    val urls = mutableListOf<RankedImageUrl>()
    var order = 0
    fun visit(
        current: JsonElement,
        key: String = "",
        path: List<String> = emptyList(),
    ) {
        val currentPath = path + key.lowercase()
        if (currentPath.any { it == "user" || it.contains("avatar") || it.contains("profile_image") }) return
        when (current) {
            is JsonObject -> current.forEach { (childKey, child) -> visit(child, childKey, currentPath) }
            else -> {
                val values = runCatching { current.jsonArray }.getOrNull()
                if (values != null) {
                    values.forEach { visit(it, key, path) }
                } else if (key.contains("url", true) || key.contains("image", true) || key.contains("pic", true)) {
                    runCatching { current.jsonPrimitive.content }.getOrNull()
                        ?.let { resolveHttpsUrl(base, it) }
                        ?.takeIf(::isLikelyCalendarMediaUrl)
                        ?.let { url ->
                            val mediaPath = currentPath.joinToString("/")
                            val score =
                                when {
                                    "pics" in currentPath || "pic_infos" in currentPath -> 100
                                    "page_info" in currentPath -> 25
                                    else -> 0
                                } +
                                    if (
                                        listOf("large", "largest", "original", "mw2000").any(mediaPath::contains)
                                    ) {
                                        30
                                    } else {
                                        0
                                    }
                            urls += RankedImageUrl(url, score, order++)
                        }
                }
            }
        }
    }
    visit(element)
    return urls
        .sortedWith(compareByDescending<RankedImageUrl> { it.score }.thenBy(RankedImageUrl::order))
        .distinctBy { ranked ->
            runCatching { URI(ranked.url) }.getOrNull()?.let { uri ->
                if (uri.host.endsWith(".sinaimg.cn", true) || uri.host.equals("sinaimg.cn", true)) {
                    "sinaimg:${uri.path.substringAfterLast('/')}"
                } else {
                    ranked.url
                }
            } ?: ranked.url
        }
        .map(RankedImageUrl::url)
        .take(MAX_IMAGES_PER_SOURCE)
}

private fun resolveHttpsUrl(
    base: URI,
    raw: String,
): String? {
    val cleaned = raw.replace("\\/", "/").replace("&amp;", "&").trim()
    if (cleaned.isBlank() || cleaned.startsWith("data:") || cleaned.startsWith("javascript:")) return null
    val normalized = if (cleaned.startsWith("//")) "https:$cleaned" else cleaned
    val resolved = runCatching { base.resolve(normalized) }.getOrNull() ?: return null
    return resolved.toString().takeIf { resolved.scheme.equals("https", true) && !resolved.host.isNullOrBlank() }
}

private fun isLikelyCalendarMediaUrl(url: String): Boolean {
    val lower = url.lowercase()
    if (listOf("avatar", "icon", "logo", "emoji", "emoticon").any(lower::contains)) return false
    val host = runCatching { URI(url).host.lowercase() }.getOrNull() ?: return false
    return CALENDAR_MEDIA_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") }
}

private fun mergeIngestionShows(shows: List<CalendarIngestionShow>): List<CalendarIngestionShow> =
    shows
        .groupBy { show -> "title:${normalizeTitle(show.title)}" }
        .values
        .map { matching ->
            val preferred = matching.firstOrNull { it.tmdbId != null } ?: matching.first()
            preferred.copy(
                platforms = matching.flatMap(CalendarIngestionShow::platforms).distinct(),
                posterPath = preferred.posterPath ?: matching.firstNotNullOfOrNull(CalendarIngestionShow::posterPath),
                discoveryWeight = matching.maxOf(CalendarIngestionShow::discoveryWeight),
                sources = matching.flatMap(CalendarIngestionShow::sources).distinctBy(CalendarSourceConfig::url),
            )
        }.groupBy { show -> show.tmdbId?.let { "tmdb:$it" } ?: "title:${normalizeTitle(show.title)}" }
        .values
        .map { matching ->
            val preferred = matching.maxBy(CalendarIngestionShow::discoveryWeight)
            preferred.copy(
                platforms = matching.flatMap(CalendarIngestionShow::platforms).distinct(),
                sources = matching.flatMap(CalendarIngestionShow::sources).distinctBy(CalendarSourceConfig::url),
            )
        }

private fun extractCalendarImages(
    pageUrl: String,
    html: String,
): List<String> {
    val base = URI(pageUrl)
    val pageIsCalendar = CALENDAR_DISCOVERY_KEYWORDS.containsMatchIn(htmlToText(html))
    return ARCHIVE_IMAGE_REGEX.findAll(html)
        .filter { match ->
            val tag = match.value.lowercase()
            listOf("日历", "calendar", "schedule", "追剧", "排期").any(tag::contains) || pageIsCalendar
        }.mapNotNull { match ->
            val candidate = match.groupValues[1].substringBefore(',').trim().substringBefore(' ')
            resolveHttpsUrl(base, candidate)?.takeIf(::isLikelyCalendarMediaUrl)
        }
        .distinct()
        .take(MAX_IMAGES_PER_SOURCE)
        .toList()
}

private fun requireHttps(value: String): URI =
    URI(value).also { uri ->
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "HTTPS URL required" }
        require(uri.userInfo == null) { "Credentials are not allowed in calendar URLs" }
    }

internal fun validateCalendarRendererEndpoint(value: String): URI =
    URI(value).also { uri ->
        val isHttps = uri.scheme.equals("https", true)
        val isLoopbackHttp =
            uri.scheme.equals("http", true) &&
                uri.host?.lowercase() in setOf("127.0.0.1", "::1", "localhost")
        require((isHttps || isLoopbackHttp) && !uri.host.isNullOrBlank()) {
            "Renderer endpoint must use HTTPS or loopback HTTP"
        }
        require(uri.userInfo == null) { "Credentials are not allowed in renderer URLs" }
    }

private fun nextRevision(
    previous: String?,
    today: LocalDate,
): String {
    val prefix = today.toString()
    val sequence =
        previous
            ?.takeIf { it.startsWith("$prefix-r") }
            ?.substringAfterLast("-r")
            ?.toIntOrNull()
            ?.plus(1)
            ?: 1
    return "$prefix-r$sequence"
}

internal fun normalizeTitle(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

internal fun semanticFingerprint(schedules: List<CalendarSeries>): String =
    ingestionJson
        .encodeToString(
            schedules
                .sortedBy(CalendarSeries::tmdbId)
                .map { schedule ->
                    schedule.copy(
                        revision = "",
                        updatedAt = "",
                        evidence = schedule.evidence.map { it.copy(capturedAt = "", contentHash = "") },
                    )
                },
        ).sha256()

internal fun validateOcrProviders(providers: List<CalendarOcrProviderConfig>) {
    require(providers.size <= MAX_OCR_PROVIDERS)
    require(providers.map(CalendarOcrProviderConfig::id).distinct().size == providers.size)
    require(
        providers
            .map { requireHttps(it.endpoint).normalize().toString().trimEnd('/') }
            .distinct()
            .size == providers.size,
    ) { "OCR providers must use distinct endpoints" }
    providers.forEach {
        require(it.id.isNotBlank() && it.id.length <= 40)
        val endpoint = requireHttps(it.endpoint)
        require(it.protocol in setOf(OCR_PROTOCOL_BRIDGE, OCR_PROTOCOL_PADDLE_JOBS, OCR_PROTOCOL_OCR_SPACE))
        require(it.pollIntervalMillis in 500..10_000)
        require(it.pollTimeoutSeconds in 15..300)
        if (it.protocol == OCR_PROTOCOL_PADDLE_JOBS) {
            require(endpoint.host.equals(PADDLE_OCR_HOST, ignoreCase = true)) {
                "PaddleOCR credentials may only be sent to the official API host"
            }
            require(endpoint.path.trimEnd('/') == PADDLE_OCR_JOBS_PATH)
            require(!it.apiKeyEnvironment.isNullOrBlank())
            require((it.model ?: DEFAULT_PADDLE_OCR_MODEL) in SUPPORTED_PADDLE_OCR_MODELS)
        }
        if (it.protocol == OCR_PROTOCOL_OCR_SPACE) {
            require(endpoint.host.equals(OCR_SPACE_HOST, ignoreCase = true)) {
                "OCR.space credentials may only be sent to the official API host"
            }
            require(endpoint.path.trimEnd('/').equals(OCR_SPACE_PARSE_PATH, ignoreCase = true))
            require(!it.apiKeyEnvironment.isNullOrBlank())
            require((it.engine ?: DEFAULT_OCR_SPACE_ENGINE) in 1..3)
            require((it.language ?: DEFAULT_OCR_SPACE_LANGUAGE).matches(Regex("[a-z]{3}|auto")))
        }
    }
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

private val PLATFORM_HOSTS =
    mapOf(
        "爱奇艺" to setOf("iqiyi.com"),
        "优酷" to setOf("youku.com"),
        "腾讯视频" to setOf("v.qq.com"),
        "芒果TV" to setOf("mgtv.com"),
    )

private const val MAX_IMAGES_PER_SOURCE = 6
private const val MAX_CALENDAR_EPISODES = 500
private const val MAX_OCR_PROVIDERS = 2
private const val MIN_PARTIAL_OCR_COORDINATES = 3
private const val MAX_SOURCES_PER_SHOW = 9
private const val MAX_EVIDENCE_PER_SERIES = 20
private const val MAX_SOURCE_CHARS = 4_000_000
private const val MAX_TVMAZE_SCHEDULE_CHARS = 32_000_000
private val TVMAZE_SHOW_TYPES =
    setOf("Scripted", "Animation", "Documentary", "Reality", "Talk Show", "Game Show", "News", "Sports", "Variety")
private const val SOURCE_FETCH_ATTEMPTS = 3
private const val SOURCE_FETCH_RETRY_BASE_MS = 350L
private const val MAX_OCR_RESULT_CHARS = 4_000_000
private const val OCR_PROTOCOL_BRIDGE = "Bridge"
private const val OCR_PROTOCOL_PADDLE_JOBS = "PaddleOcrJobs"
private const val OCR_PROTOCOL_OCR_SPACE = "OcrSpace"
private const val PADDLE_OCR_HOST = "paddleocr.aistudio-app.com"
private const val PADDLE_OCR_JOBS_PATH = "/api/v2/ocr/jobs"
private const val PADDLE_OCR_VL_MODEL = "PaddleOCR-VL-1.6"
private const val PADDLE_OCR_V6_MODEL = "PP-OCRv6"
private const val PADDLE_STRUCTURE_V3_MODEL = "PP-StructureV3"
private const val DEFAULT_PADDLE_OCR_MODEL = PADDLE_STRUCTURE_V3_MODEL
private val SUPPORTED_PADDLE_OCR_MODELS =
    setOf(PADDLE_OCR_VL_MODEL, PADDLE_OCR_V6_MODEL, PADDLE_STRUCTURE_V3_MODEL)
private const val OCR_SPACE_HOST = "api.ocr.space"
private const val OCR_SPACE_PARSE_PATH = "/parse/image"
private const val DEFAULT_OCR_SPACE_ENGINE = 3
private const val DEFAULT_OCR_SPACE_LANGUAGE = "auto"
private const val MAX_DISCOVERY_FEEDS = 20
private const val MAX_DISCOVERED_SHOWS = 200
private const val MAX_DOMESTIC_CANDIDATES = 500
private const val MAX_PLATFORM_CATALOGS = 20
private const val MAX_CANDIDATE_EVIDENCE_TEXT_CHARS = 20_000
private const val DISCOVERED_SCHEDULE_RETENTION_DAYS = 45
private const val DISCOVERY_REQUEST_CONCURRENCY = 4
private const val SHOW_INGESTION_CONCURRENCY = 4
private const val SOURCE_INGESTION_CONCURRENCY = 6
private const val OCR_REQUEST_CONCURRENCY = 4
private const val PADDLE_REQUEST_CONCURRENCY = 1
private const val PADDLE_SUBMIT_ATTEMPTS = 6
private const val PADDLE_SUBMIT_RETRY_MILLIS = 2_000L
private const val PADDLE_QUEUE_FULL_CODE = 10010
private const val OCR_SPACE_REQUEST_CONCURRENCY = 1
private const val OCR_SPACE_ATTEMPTS = 3
private const val OCR_SPACE_RETRY_MILLIS = 1_500L
private const val DEFAULT_DISCOVERY_TITLE_PATTERN =
    "[《「『]([^》」』]{1,120})[》」』].{0,40}(?:追剧日历|播出日历|更新日历|排期|更新时间)"
private val ARCHIVE_ANCHOR_REGEX =
    Regex("(?is)<a\\b([^>]*?)href=[\"']([^\"']+)[\"']([^>]*)>(.*?)</a>")
private val ARCHIVE_IMAGE_REGEX =
    Regex(
        "(?is)<img[^>]+(?:src|data-src|data-original|data-lazy-src|data-actualsrc|srcset)=" +
            "[\"']([^\"']+)[\"'][^>]*>",
    )
private val CALENDAR_DISCOVERY_KEYWORDS =
    Regex("(?:追剧|播出|更新|会员|加更|观看|暗恋)日历|排期|更新时间|观看指引|通关攻略")
private val CALENDAR_CANDIDATE_EVIDENCE_KEYWORDS =
    Regex("日历|排期|更新|首更|开播|收官|点映|会员|SVIP|VIP|追剧|观看指引", RegexOption.IGNORE_CASE)
private val CALENDAR_HASHTAG_TITLE_PATTERNS =
    listOf(
        Regex("#([^#]{1,80}?)(?:追剧|播出|更新|会员|加更|观看|暗恋)日历#"),
        Regex("[《「『]([^》」』]{1,120})[》」』].{0,120}(?:追剧|播出|更新|会员|加更|观看)日历"),
    )
private val GENERIC_CALENDAR_HASHTAGS =
    setOf("追剧日历", "播出日历", "更新日历", "会员日历", "最新追剧日历", "大结局点映礼", "点映礼")
private val WEIBO_PERMALINK_PATH = Regex("^/\\d{6,}/[A-Za-z0-9_-]{5,}/?$")
private val ARCHIVE_JSON_TEXT_KEYS =
    listOf("text_raw", "text", "content", "title", "desc", "description", "shareTitle")
private val ARCHIVE_JSON_LINK_KEYS =
    listOf("url", "href", "link", "page_url", "share_url", "shareUrl")
private val ARCHIVE_JSON_POST_ID_KEYS = listOf("mblogid", "bid")
private val CALENDAR_MEDIA_HOST_SUFFIXES =
    setOf(
        "sinaimg.cn",
        "iqiyipic.com",
        "qiyipic.com",
        "ykimg.com",
        "alicdn.com",
        "gtimg.com",
        "qpic.cn",
        "qq.com",
        "mgtv.com",
        "mgtvcdn.com",
        "hitv.com",
    )
private const val MAX_ARCHIVE_ENTRY_CHARS = 250_000
