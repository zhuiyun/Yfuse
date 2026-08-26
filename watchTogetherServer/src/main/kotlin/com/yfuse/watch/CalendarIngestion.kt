package com.yfuse.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
internal data class CalendarIngestionConfig(
    val refreshMinutes: Int = 30,
    val verifiedAccounts: List<VerifiedCalendarAccount> = emptyList(),
    val ocrProviders: List<CalendarOcrProviderConfig> = emptyList(),
    val shows: List<CalendarIngestionShow> = emptyList(),
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
}

@Serializable
internal data class CalendarIngestionShow(
    val title: String,
    val year: Int,
    val tmdbId: Int? = null,
    val seasonNumber: Int = 1,
    val posterPath: String? = null,
    val airTime: String = "12:00",
    val timeZoneId: String = "Asia/Shanghai",
    val platforms: List<String>,
    val accessTier: String = "Member",
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
}

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

    suspend fun runOnce(): Boolean =
        withContext(Dispatchers.IO) {
            val config = ingestionJson.decodeFromString<CalendarIngestionConfig>(configFile.readText())
            validateConfig(config)
            val generatedAt = Instant.now().toString()
            val existing = readExistingPublication()
            val fallbackSchedules =
                (existing?.schedules.orEmpty() + DEFAULT_CALENDAR_SCHEDULES)
                    .distinctBy(CalendarSeries::tmdbId)
            val provisionalRevision = nextRevision(existing?.revision, LocalDate.now(ZoneOffset.UTC))
            val schedules =
                config.shows.mapNotNull { show ->
                    val identity =
                        resolveIdentity(show)
                            ?: return@mapNotNull fallbackSchedules
                                .firstOrNull { current ->
                                    current.tmdbId == show.tmdbId ||
                                        normalizeTitle(current.title) == normalizeTitle(show.title)
                                }?.copy(revision = provisionalRevision)
                    val sources = show.sources.mapNotNull { captureSource(show, it, config) }
                    CalendarEvidenceGate.compile(show, identity, sources, provisionalRevision, generatedAt)
                        ?: fallbackSchedules
                            .firstOrNull { it.tmdbId == identity.tmdbId }
                            ?.copy(revision = provisionalRevision)
                }.distinctBy(CalendarSeries::tmdbId)
            if (schedules.isEmpty()) return@withContext false
            val contentFingerprint = semanticFingerprint(schedules)
            val oldFingerprint = existing?.schedules?.let(::semanticFingerprint)
            if (contentFingerprint == oldFingerprint) return@withContext false
            val publication = CalendarPublication(provisionalRevision, generatedAt, schedules)
            validateCalendarPublication(publication)
            scheduleStore.replace(publication)
            runCatching { writeAtomically(publication) }
                .onFailure { failure ->
                    if (scheduleStore === NoOpCalendarScheduleStore) throw failure
                    System.err.println("calendar JSON snapshot failed: ${failure.message}")
                }
            true
        }

    private fun captureSource(
        show: CalendarIngestionShow,
        source: CalendarSourceConfig,
        config: CalendarIngestionConfig,
    ): ParsedCalendarSource? {
        validateSource(source, config)
        val capturedAt = Instant.now().toString()
        val html = fetchText(source.url) ?: return null
        val pageText = htmlToText(html)
        val pageEpisodes = ChineseScheduleParser.parse(pageText, show.year, show.accessTier)
        val imageUrls =
            (if (source.imageUrls.isNotEmpty()) source.imageUrls else extractCalendarImages(source.url, html))
                .distinct()
                .take(MAX_IMAGES_PER_SOURCE)
        val ocrCaptures = mutableListOf<OcrConsensusCapture>()
        imageUrls.forEach { imageUrl ->
            val readings =
                config.ocrProviders.take(MAX_OCR_PROVIDERS).mapNotNull { provider ->
                    ocr(provider, imageUrl)?.let { text ->
                        OcrReading(
                            providerId = provider.id,
                            text = text,
                            episodes = ChineseScheduleParser.parse(text, show.year, show.accessTier),
                        )
                    }
                }
            if (readings.size != MAX_OCR_PROVIDERS) return@forEach
            val resolution = OcrConfidenceGate.resolve(readings, show.year, show.accessTier)
            if (resolution.conflict) return null
            if (resolution.episodes.isEmpty() || resolution.agreement == OcrAgreement.None) return@forEach
            ocrCaptures +=
                OcrConsensusCapture(
                    imageUrl = imageUrl,
                    episodes = resolution.episodes,
                    readingHashes = readings.map { reading -> reading.providerId to reading.text.sha256() },
                    agreement = resolution.agreement,
                )
        }
        val ocrConsensus = ocrCaptures.isNotEmpty()
        val combined = mergeWithoutConflict(listOf(pageEpisodes) + ocrCaptures.map(OcrConsensusCapture::episodes)) ?: return null
        if (combined.isEmpty()) return null
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
        val encodedTitle = java.net.URLEncoder.encode(show.title, Charsets.UTF_8)
        val url = "https://api.themoviedb.org/3/search/tv?language=zh-CN&query=$encodedTitle&year=${show.year}"
        val request =
            HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response =
            runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299) return null
        val candidates = ingestionJson.parseToJsonElement(response.body()).jsonObject["results"]?.jsonArray.orEmpty()
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
                    response.body().sha256(),
                )
            }
        return exact.singleOrNull()
    }

    private fun ocr(
        provider: CalendarOcrProviderConfig,
        imageUrl: String,
    ): String? {
        val key = provider.apiKeyEnvironment?.let(System::getenv)
        if (provider.apiKeyEnvironment != null && key.isNullOrBlank()) return null
        return when (provider.protocol) {
            OCR_PROTOCOL_BRIDGE -> bridgeOcr(provider, imageUrl, key)
            OCR_PROTOCOL_PADDLE_JOBS -> paddleOcr(provider, imageUrl, key ?: return null)
            OCR_PROTOCOL_OCR_SPACE -> ocrSpace(provider, imageUrl, key ?: return null)
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
        val submitResponse =
            runCatching { http.send(submitRequest, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                ?: return null
        if (submitResponse.statusCode() !in 200..299) return null
        val jobId = PaddleOcrResponseParser.submittedJobId(submitResponse.body()) ?: return null
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
        val response =
            runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
                ?: return null
        if (response.statusCode() !in 200..299 || response.body().length > MAX_OCR_RESULT_CHARS) return null
        return OcrSpaceResponseParser.extractText(response.body())
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

    private fun fetchText(url: String): String? {
        val request =
            HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(12))
                .header("User-Agent", "YfuseCalendarBot/1.0 (+official-schedule-evidence)")
                .GET()
                .build()
        val response =
            runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }
                .getOrNull()
                ?: return null
        return response.body().takeIf { response.statusCode() in 200..299 && it.length <= MAX_SOURCE_CHARS }
    }

    private fun validateConfig(config: CalendarIngestionConfig) {
        require(config.refreshMinutes in 15..1_440)
        require(config.shows.size <= 1_000)
        validateOcrProviders(config.ocrProviders)
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
            require(show.title.isNotBlank() && show.title.length <= 120)
            require(show.year in 1900..2100)
            require(show.seasonNumber in 1..100)
            require(runCatching { LocalTime.parse(show.airTime) }.isSuccess)
            require(runCatching { ZoneId.of(show.timeZoneId) }.isSuccess)
            require(show.platforms.isNotEmpty() && show.platforms.all(PLATFORM_HOSTS::containsKey))
            require(show.accessTier in setOf("Unknown", "Free", "Member", "SviP"))
            require(show.sources.isNotEmpty() && show.sources.size <= MAX_SOURCES_PER_SHOW)
        }
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
            runCatching { runtime.runOnce() }.onFailure { failure ->
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

private fun extractCalendarImages(
    pageUrl: String,
    html: String,
): List<String> {
    val base = URI(pageUrl)
    return Regex("(?is)<img[^>]+(?:src|data-src)=[\"']([^\"']+)[\"'][^>]*>")
        .findAll(html)
        .filter { match ->
            val tag = match.value.lowercase()
            listOf("日历", "calendar", "schedule", "追剧", "排期").any(tag::contains)
        }.mapNotNull { match -> runCatching { base.resolve(match.groupValues[1]).toString() }.getOrNull() }
        .filter { runCatching { requireHttps(it) }.isSuccess }
        .toList()
}

private fun requireHttps(value: String): URI =
    URI(value).also { uri ->
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) { "HTTPS URL required" }
        require(uri.userInfo == null) { "Credentials are not allowed in calendar URLs" }
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

private fun normalizeTitle(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

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
