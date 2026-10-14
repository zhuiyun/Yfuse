package com.yfuse.watch

import kotlinx.serialization.json.put
import java.time.LocalDate

internal data class OcrConsensusResolution(
    val episodes: Map<Int, String> = emptyMap(),
    val agreement: OcrAgreement = OcrAgreement.None,
    val conflict: Boolean = false,
    val providerIds: Set<String> = emptySet(),
)

internal object OcrConfidenceGate {
    fun resolve(
        readings: List<OcrReading>,
        defaultYear: Int,
        accessTier: String,
    ): OcrConsensusResolution {
        val distinct = readings.distinctBy(OcrReading::providerId)
        if (distinct.size !in MIN_OCR_PROVIDERS..MAX_OCR_PROVIDERS) {
            return OcrConsensusResolution()
        }
        val pairs =
            distinct.indices.flatMap { first ->
                ((first + 1) until distinct.size).mapNotNull { second ->
                    val left = distinct[first]
                    val right = distinct[second]
                    if (left.independenceGroup == right.independenceGroup) null else left to right
                }
            }
        if (pairs.isEmpty()) return OcrConsensusResolution()
        val pairResolutions = pairs.map { (first, second) -> resolvePair(first, second, defaultYear, accessTier) }
        if (distinct.size == MIN_OCR_PROVIDERS) return pairResolutions.single()
        val accepted = pairResolutions.filter { it.episodes.isNotEmpty() && it.agreement != OcrAgreement.None }
        if (accepted.isEmpty()) return OcrConsensusResolution(conflict = pairResolutions.any { it.conflict })
        val merged =
            mergeWithoutConflict(accepted.map(OcrConsensusResolution::episodes))
                ?: return OcrConsensusResolution(conflict = true)
        return OcrConsensusResolution(
            episodes = merged,
            agreement = OcrAgreement.Majority,
            providerIds = accepted.flatMap(OcrConsensusResolution::providerIds).toSet(),
        )
    }

    private fun resolvePair(
        first: OcrReading,
        second: OcrReading,
        defaultYear: Int,
        accessTier: String,
    ): OcrConsensusResolution {
        val providerIds = setOf(first.providerId, second.providerId)
        val pair = listOf(first, second)
        val dailyGrids = pair.map { dailyGridCoordinates(it.text, defaultYear, accessTier) }
        if (dailyGrids.all { it.isNotEmpty() } && dailyGrids.distinct().size == 1) {
            return OcrConsensusResolution(
                dailyGrids.first(),
                OcrAgreement.SemanticCorroboration,
                providerIds = providerIds,
            )
        }
        val sharedEpisodes = first.episodes.keys intersect second.episodes.keys
        if (sharedEpisodes.any { first.episodes[it] != second.episodes[it] }) {
            return OcrConsensusResolution(conflict = true)
        }
        if (first.episodes.isNotEmpty() && first.episodes == second.episodes) {
            return OcrConsensusResolution(first.episodes, OcrAgreement.Exact, providerIds = providerIds)
        }

        val nonEmpty = pair.filter { it.episodes.isNotEmpty() }
        if (nonEmpty.size == 2) {
            val smaller = nonEmpty.minBy { it.episodes.size }
            val larger = nonEmpty.maxBy { it.episodes.size }
            val smallerIsSubset = smaller.episodes.all { (episode, date) -> larger.episodes[episode] == date }
            if (smaller.episodes.size >= MIN_PARTIAL_OCR_COORDINATES && smallerIsSubset) {
                return OcrConsensusResolution(larger.episodes, OcrAgreement.PartialSubset, providerIds = providerIds)
            }
            val intersection =
                sharedEpisodes
                    .filter { first.episodes[it] == second.episodes[it] }
                    .associateWith { first.episodes.getValue(it) }
            if (intersection.size >= MIN_PARTIAL_OCR_COORDINATES) {
                return OcrConsensusResolution(
                    intersection,
                    OcrAgreement.CoordinateIntersection,
                    providerIds = providerIds,
                )
            }
        }

        val fullReleaseDates = pair.map { fullReleaseDate(it.text, defaultYear, accessTier) }
        if (fullReleaseDates.all { it != null } && fullReleaseDates.distinct().size == 1) {
            val episodeCount = pair.maxOfOrNull { fullSeriesEpisodeCount(it.text) } ?: 0
            if (episodeCount in 2..MAX_CALENDAR_EPISODES) {
                val airDate = fullReleaseDates.first()!!.toString()
                return OcrConsensusResolution(
                    episodes = (1..episodeCount).associateWith { airDate },
                    agreement = OcrAgreement.SemanticCorroboration,
                    providerIds = providerIds,
                )
            }
        }

        val candidate = nonEmpty.maxByOrNull { it.episodes.size }?.episodes.orEmpty()
        if (candidate.isNotEmpty() && semanticallyCorroborated(candidate, pair, defaultYear, accessTier)) {
            return OcrConsensusResolution(candidate, OcrAgreement.SemanticCorroboration, providerIds = providerIds)
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
        FULL_SERIES_RANGE_PATTERN
            .findAll(normalizeOcrText(raw))
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
        val parsed =
            candidate.mapValues { (_, date) ->
                runCatching { LocalDate.parse(date) }.getOrNull()
                    ?: return false
            }
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
            DAILY_GRID_EPISODE_PATTERN
                .findAll(text)
                .mapNotNull { match ->
                    val first = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    val last = match.groupValues[2].toIntOrNull() ?: first
                    (first..last).takeIf { first in 1..MAX_CALENDAR_EPISODES && last in first..MAX_CALENDAR_EPISODES }
                }.distinctBy { it.first to it.last }
                .toList()
        val groups =
            ranges
                .filterNot { candidate ->
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
        raw
            .replace(Regex("(?is)<[^>]+>"), " ")
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
