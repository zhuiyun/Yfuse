package com.yfuse.watch

import kotlinx.serialization.Serializable

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
    val independenceGroup: String? = null,
    val pollIntervalMillis: Long = 2_000,
    val pollTimeoutSeconds: Long = 120,
)

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
    Majority,
}

internal data class OcrReading(
    val providerId: String,
    val text: String,
    val episodes: Map<Int, String>,
    val independenceGroup: String = providerId,
)

internal data class OcrConsensusCapture(
    val imageUrl: String,
    val episodes: Map<Int, String>,
    val readingHashes: List<Pair<String, String>>,
    val agreement: OcrAgreement,
)
