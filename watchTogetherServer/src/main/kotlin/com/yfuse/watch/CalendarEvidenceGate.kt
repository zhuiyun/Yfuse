package com.yfuse.watch

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
                                        OcrAgreement.Majority -> "three-ocr-two-provider-majority"
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

internal fun mergeWithoutConflict(parts: List<Map<Int, String>>): Map<Int, String>? {
    val merged = linkedMapOf<Int, String>()
    parts.forEach { part ->
        part.forEach { (episode, date) ->
            val previous = merged.putIfAbsent(episode, date)
            if (previous != null && previous != date) return null
        }
    }
    return merged
}

internal fun ocrConfidenceBonus(agreement: OcrAgreement): Int =
    when (agreement) {
        OcrAgreement.Exact -> 20
        OcrAgreement.PartialSubset -> 15
        OcrAgreement.CoordinateIntersection -> 10
        OcrAgreement.SemanticCorroboration -> 15
        OcrAgreement.Majority -> 20
        OcrAgreement.None -> 0
    }
