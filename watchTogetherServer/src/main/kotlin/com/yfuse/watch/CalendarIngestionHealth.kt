package com.yfuse.watch

import kotlinx.serialization.Serializable
import java.time.Instant

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
    val ocrCacheHits: Int = 0,
    val ocrFailureCacheHits: Int = 0,
    val ocrProviderRequests: Int = 0,
    val showDiagnostics: List<CalendarShowDiagnostic> = emptyList(),
    val message: String? = null,
)

@Serializable
internal data class CalendarShowDiagnostic(
    val title: String,
    val state: String = "pending",
    val reasons: List<String> = emptyList(),
    val sourceCount: Int = 0,
    val imageCount: Int = 0,
)

internal object CalendarIngestionHealth {
    @Volatile
    private var value = CalendarIngestionStatus()
    private val diagnostics = linkedMapOf<String, CalendarShowDiagnostic>()

    @Synchronized
    fun snapshot(): CalendarIngestionStatus = value.copy(showDiagnostics = diagnostics.values.toList())

    @Synchronized
    fun running(
        configuredShows: Int,
        discoveredShows: Int,
    ) {
        diagnostics.clear()
        value =
            CalendarIngestionStatus(
                state = "running",
                lastStartedAt = Instant.now().toString(),
                configuredShows = configuredShows,
                discoveredShows = discoveredShows,
            )
    }

    @Synchronized
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

    @Synchronized
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

    @Synchronized
    fun failed(failure: Throwable) {
        value =
            value.copy(
                state = "failed",
                lastFinishedAt = Instant.now().toString(),
                changed = false,
                message = failure.message?.take(240) ?: failure::class.simpleName,
            )
    }

    @Synchronized
    fun registerShows(shows: List<CalendarIngestionShow>) {
        shows.asSequence().filter { it.origin == "Domestic" }.take(MAX_STATUS_SHOW_DIAGNOSTICS).forEach { show ->
            diagnostics.putIfAbsent(
                normalizeTitle(show.title),
                CalendarShowDiagnostic(
                    title = show.title,
                    sourceCount = show.sources.size,
                    imageCount = show.sources.sumOf { it.imageUrls.size },
                ),
            )
        }
    }

    @Synchronized
    fun rejected(
        show: CalendarIngestionShow,
        reason: String,
    ) {
        val key = normalizeTitle(show.title)
        val previous = diagnostics[key] ?: CalendarShowDiagnostic(show.title)
        diagnostics[key] =
            previous.copy(
                state = "rejected",
                reasons = (previous.reasons + reason).distinct().take(MAX_STATUS_REASONS_PER_SHOW),
            )
    }

    @Synchronized
    fun completed(
        show: CalendarIngestionShow,
        state: String,
    ) {
        val key = normalizeTitle(show.title)
        val previous = diagnostics[key] ?: CalendarShowDiagnostic(show.title)
        diagnostics[key] = previous.copy(state = state)
    }

    @Synchronized
    fun ocrCacheHit(failure: Boolean) {
        value =
            if (failure) {
                value.copy(ocrFailureCacheHits = value.ocrFailureCacheHits + 1)
            } else {
                value.copy(ocrCacheHits = value.ocrCacheHits + 1)
            }
    }

    @Synchronized
    fun ocrProviderRequest() {
        value = value.copy(ocrProviderRequests = value.ocrProviderRequests + 1)
    }
}

private const val MAX_STATUS_SHOW_DIAGNOSTICS = 200
private const val MAX_STATUS_REASONS_PER_SHOW = 8
