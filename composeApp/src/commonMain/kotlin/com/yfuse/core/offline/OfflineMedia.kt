package com.yfuse.core.offline

import com.russhwolf.settings.Settings
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.AudioTrackInfo
import com.yfuse.core.model.Episode
import com.yfuse.core.model.MediaVersion
import com.yfuse.core.model.SubtitleTrackInfo
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    Queued,
    WaitingForWifi,
    Downloading,
    Paused,
    Completed,
    Failed,
}

@Serializable
enum class DownloadFailureKind {
    Authentication,
    Network,
    Server,
    Storage,
    Source,
    Unknown,
}

@Serializable
enum class OfflineDownloadQuality(
    val label: String,
    val maxWidth: Int?,
    val videoBitrateBps: Int?,
) {
    Original("原画", null, null),
    Uhd("4K", 3840, 20_000_000),
    FullHd("1080P", 1920, 8_000_000),
    Hd("720P", 1280, 4_000_000),
}

@Serializable
enum class OfflineBatchMode(
    val label: String,
) {
    Current("本集 / 本片"),
    Season("整季"),
    Unwatched("仅未看集"),
}

@Serializable
data class OfflineDownloadPolicy(
    val wifiOnly: Boolean = true,
    val maxConcurrentDownloads: Int = 2,
    val autoDeleteWatched: Boolean = false,
) {
    fun normalized(): OfflineDownloadPolicy =
        copy(
            maxConcurrentDownloads = maxConcurrentDownloads.coerceIn(1, MAX_CONCURRENT_OFFLINE_DOWNLOADS),
        )
}

const val MAX_CONCURRENT_OFFLINE_DOWNLOADS = 3

data class OfflineBatchItem(
    val itemId: String,
    val played: Boolean,
)

data class OfflineDownloadSelection(
    val batchMode: OfflineBatchMode = OfflineBatchMode.Current,
    val mediaSourceId: String? = null,
    val quality: OfflineDownloadQuality = OfflineDownloadQuality.Original,
    val subtitleStreamIndex: Int? = null,
    val subtitleCodec: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleDefault: Boolean = false,
    val subtitleForced: Boolean = false,
)

/** Stable batch filtering shared by the dialog and tests. */
fun selectOfflineBatchItems(
    mode: OfflineBatchMode,
    currentItemId: String,
    seasonItems: List<OfflineBatchItem>,
): List<String> {
    if (mode == OfflineBatchMode.Current || seasonItems.isEmpty()) return listOf(currentItemId)
    return seasonItems
        .asSequence()
        .filter { mode != OfflineBatchMode.Unwatched || !it.played }
        .map(OfflineBatchItem::itemId)
        .filter(String::isNotBlank)
        .distinct()
        .toList()
}

/** A replacement is required when any byte-producing choice changes. */
fun sameOfflineDownloadVariant(
    itemId: String,
    oldSourceId: String?,
    newSourceId: String?,
    oldQuality: OfflineDownloadQuality,
    newQuality: OfflineDownloadQuality,
    oldSubtitleIndex: Int?,
    newSubtitleIndex: Int?,
): Boolean =
    (oldSourceId ?: itemId) == (newSourceId ?: itemId) &&
        oldQuality == newQuality &&
        oldSubtitleIndex == newSubtitleIndex

fun selectPendingOfflineDownloads(
    items: List<OfflineMedia>,
    nowMs: Long,
    maxConcurrentDownloads: Int,
): List<OfflineMedia> =
    items
        .asSequence()
        .filter {
            it.status in setOf(DownloadStatus.Queued, DownloadStatus.WaitingForWifi) &&
                it.nextRetryAt <= nowMs
        }.take(maxConcurrentDownloads.coerceIn(1, MAX_CONCURRENT_OFFLINE_DOWNLOADS))
        .toList()

/**
 * Estimate before queuing. Original files use the server's exact size when available;
 * transcoded choices use their bitrate cap plus AAC audio. Overflow saturates instead of
 * wrapping to a misleading negative value.
 */
fun estimateOfflineBytes(
    sourceSizeBytes: Long?,
    sourceBitrateBps: Int?,
    runtimeMinutes: Int?,
    quality: OfflineDownloadQuality,
    includeSubtitle: Boolean = false,
): Long? {
    if (quality == OfflineDownloadQuality.Original) {
        sourceSizeBytes?.takeIf { it > 0L }?.let { exact ->
            return exact.saturatingAdd(if (includeSubtitle) OFFLINE_SUBTITLE_ESTIMATE_BYTES else 0L)
        }
    }
    val minutes = runtimeMinutes?.takeIf { it > 0 } ?: return null
    val requestedBitrate =
        quality.videoBitrateBps
            ?: sourceBitrateBps?.takeIf { it > 0 }
            ?: DEFAULT_OFFLINE_ESTIMATE_BITRATE_BPS
    val videoBitrate =
        sourceBitrateBps
            ?.takeIf { it > 0 }
            ?.let { minOf(it, requestedBitrate) }
            ?: requestedBitrate
    val totalBitrate = videoBitrate.toLong() + OFFLINE_AUDIO_ESTIMATE_BITRATE_BPS
    val seconds = minutes.toLong().saturatingMultiply(60L)
    val bytes = seconds.saturatingMultiply(totalBitrate) / 8L
    return bytes.saturatingAdd(if (includeSubtitle) OFFLINE_SUBTITLE_ESTIMATE_BYTES else 0L)
}

private const val DEFAULT_OFFLINE_ESTIMATE_BITRATE_BPS = 8_000_000
private const val OFFLINE_AUDIO_ESTIMATE_BITRATE_BPS = 192_000L
private const val OFFLINE_SUBTITLE_ESTIMATE_BYTES = 2L * 1024L * 1024L

private fun Long.saturatingAdd(other: Long): Long =
    if (other > 0L && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingMultiply(other: Long): Long =
    when {
        this == 0L || other == 0L -> 0L
        this > Long.MAX_VALUE / other -> Long.MAX_VALUE
        else -> this * other
    }

internal const val MAX_OFFLINE_RETRY_COUNT = 5
internal const val OFFLINE_RETRY_BASE_DELAY_MS = 30_000L
internal const val OFFLINE_RETRY_MAX_DELAY_MS = 15L * 60L * 1_000L

internal data class OfflineRetryPlan(
    val retryCount: Int,
    val nextRetryAt: Long,
)

/**
 * Returns the next automatic retry, or null for terminal failures and an exhausted budget.
 * The retry number is persisted with the item so process death cannot reset the budget.
 */
internal fun planOfflineRetry(
    failureKind: DownloadFailureKind,
    currentRetryCount: Int,
    nowMs: Long,
): OfflineRetryPlan? {
    if (failureKind !in setOf(DownloadFailureKind.Network, DownloadFailureKind.Server)) return null
    val nextCount = currentRetryCount.coerceAtLeast(0) + 1
    if (nextCount > MAX_OFFLINE_RETRY_COUNT) return null
    val exponent = (nextCount - 1).coerceIn(0, 30)
    val delay =
        (OFFLINE_RETRY_BASE_DELAY_MS shl exponent)
            .coerceAtMost(OFFLINE_RETRY_MAX_DELAY_MS)
    return OfflineRetryPlan(
        retryCount = nextCount,
        nextRetryAt = if (nowMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowMs + delay,
    )
}

@Serializable
data class OfflineMedia(
    val id: String,
    val serverId: String,
    val itemId: String,
    val title: String,
    /** Specific Emby file selection; the authenticated URL is rebuilt at download time. */
    val mediaSourceId: String? = null,
    val quality: OfflineDownloadQuality = OfflineDownloadQuality.Original,
    /** Stream metadata only; authenticated subtitle URLs are rebuilt at download time. */
    val subtitleStreamIndex: Int? = null,
    val subtitleCodec: String? = null,
    val subtitleLanguage: String? = null,
    /** Read-once compatibility with v1 indexes; sanitized to null immediately after loading. */
    @SerialName("sourceUrl") val legacySourceUrl: String? = null,
    val posterUrl: String? = null,
    val localPath: String? = null,
    val subtitlePath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    /** Monotonic request generation used to prevent an old stream mutating a replacement. */
    val downloadRevision: Long = 0L,
    /** Strong ETag or Last-Modified value used with If-Range for safe continuation. */
    val resumeValidator: String? = null,
    val status: DownloadStatus = DownloadStatus.Queued,
    val error: String? = null,
    /** Number of automatic attempts already scheduled for the current failure chain. */
    val retryCount: Int = 0,
    /** Epoch milliseconds for the next eligible automatic attempt; zero means no delay. */
    val nextRetryAt: Long = 0L,
    /** Stable, user-actionable reason for the latest failure. */
    val lastFailureKind: DownloadFailureKind? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    val progress: Float
        get() =
            if (totalBytes > 0L) {
                (downloadedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }

    val playable: Boolean get() = status == DownloadStatus.Completed && localPath != null
}

@Serializable
data class OfflineDownloadRequest(
    val serverId: String,
    val itemId: String,
    val title: String,
    val mediaSourceId: String? = null,
    val quality: OfflineDownloadQuality = OfflineDownloadQuality.Original,
    val subtitleStreamIndex: Int? = null,
    val subtitleCodec: String? = null,
    val subtitleLanguage: String? = null,
    val estimatedBytes: Long? = null,
)

fun buildOfflineDownloadRequests(
    serverId: String,
    currentItemId: String,
    currentTitle: String,
    currentRuntimeMinutes: Int?,
    currentVersions: List<MediaVersion>,
    seasonEpisodes: List<Episode>,
    selection: OfflineDownloadSelection,
): List<OfflineDownloadRequest> {
    val episodeIds =
        selectOfflineBatchItems(
            mode = selection.batchMode,
            currentItemId = currentItemId,
            seasonItems = seasonEpisodes.map { OfflineBatchItem(it.id, it.played) },
        )
    val referenceVersion =
        currentVersions.firstOrNull { it.id == selection.mediaSourceId }
            ?: currentVersions.firstOrNull()
    val strictSiblingMatch = selection.mediaSourceId != null && referenceVersion != null
    return episodeIds.mapNotNull { itemId ->
        val episode = seasonEpisodes.firstOrNull { it.id == itemId }
        val versions = if (itemId == currentItemId) currentVersions else episode?.versions.orEmpty()
        val selectedVersion =
            if (itemId == currentItemId) {
                versions.firstOrNull { it.id == selection.mediaSourceId }
                    ?: versions.firstOrNull()
            } else {
                if (strictSiblingMatch) {
                    matchOfflineMediaVersion(referenceVersion, versions)
                } else {
                    versions.firstOrNull()
                }
            }
        // A batch request must never silently fall back to Emby's default file when the
        // sibling exposes media sources but none resembles the version the user chose.
        // Skipping that item is safer than downloading an unrelated cut, commentary track,
        // or unexpectedly large remux. A genuinely source-less item keeps the legacy null
        // fallback because there is nothing meaningful to compare.
        if (
            itemId != currentItemId &&
            strictSiblingMatch &&
            versions.isNotEmpty() &&
            selectedVersion == null
        ) {
            return@mapNotNull null
        }
        val selectedSubtitle =
            if (itemId == currentItemId || selection.subtitleStreamIndex == null) {
                null
            } else {
                matchOfflineSubtitleTrack(
                    tracks = selectedVersion?.subtitleTracks.orEmpty(),
                    language = selection.subtitleLanguage,
                    codec = selection.subtitleCodec,
                    default = selection.subtitleDefault,
                    forced = selection.subtitleForced,
                )
            }
        val subtitleIndex =
            if (itemId == currentItemId) selection.subtitleStreamIndex else selectedSubtitle?.index
        OfflineDownloadRequest(
            serverId = serverId,
            itemId = itemId,
            title =
                episode?.let { value ->
                    listOfNotNull(
                        value.seasonNumber?.let { "S$it" },
                        value.indexNumber?.let { "E$it" },
                        value.name,
                    ).joinToString(" ")
                } ?: currentTitle,
            mediaSourceId = selectedVersion?.id,
            quality = selection.quality,
            // Stream indices are local to one MediaSource. Sibling episodes therefore carry
            // their own matched index instead of reusing the current episode's number.
            subtitleStreamIndex = subtitleIndex,
            subtitleCodec =
                if (itemId == currentItemId) selection.subtitleCodec else selectedSubtitle?.codec,
            subtitleLanguage =
                if (itemId == currentItemId) selection.subtitleLanguage else selectedSubtitle?.language,
            estimatedBytes =
                estimateOfflineBytes(
                    sourceSizeBytes = selectedVersion?.sizeBytes,
                    sourceBitrateBps = selectedVersion?.bitrateBps,
                    runtimeMinutes = episode?.runtimeMinutes ?: currentRuntimeMinutes,
                    quality = selection.quality,
                    includeSubtitle = subtitleIndex != null,
                ),
        )
    }
}

/**
 * Select the sibling file that most closely resembles the version chosen on the current
 * episode. MediaSource ids and ordering are deliberately excluded: both commonly change per
 * episode even when every file came from the same release/encode.
 */
internal fun matchOfflineMediaVersion(
    reference: MediaVersion?,
    candidates: List<MediaVersion>,
): MediaVersion? {
    if (reference == null) return candidates.firstOrNull()
    return candidates
        .map { candidate -> candidate to candidate.offlineSimilarityTo(reference) }
        .maxByOrNull { (_, score) -> score }
        ?.takeIf { (_, score) -> score > 0 }
        ?.first
}

private fun MediaVersion.offlineSimilarityTo(reference: MediaVersion): Int {
    var score = 0
    val nameSimilarity =
        offlineNameSimilarity(
            name.normalizedOfflineFeature(),
            reference.name.normalizedOfflineFeature(),
        )
    score += (nameSimilarity * 20).toInt()
    if (container.sameOfflineFeature(reference.container)) score += 12

    val height = videoHeight ?: video?.height
    val referenceHeight = reference.videoHeight ?: reference.video?.height
    if (height != null && referenceHeight != null) {
        score +=
            when {
                height == referenceHeight -> 32
                resolutionBand(height) == resolutionBand(referenceHeight) -> 16
                else -> 0
            }
    }
    if ((videoCodec ?: video?.codec).sameOfflineFeature(reference.videoCodec ?: reference.video?.codec)) {
        score += 16
    }
    if ((videoRange ?: video?.videoRange).sameOfflineFeature(reference.videoRange ?: reference.video?.videoRange)) {
        score += 8
    }
    score += offlineAudioSimilarity(audioTracks, reference.audioTracks)

    val bitrate = bitrateBps?.takeIf { it > 0 }
    val referenceBitrate = reference.bitrateBps?.takeIf { it > 0 }
    if (bitrate != null && referenceBitrate != null) {
        val difference = if (bitrate >= referenceBitrate) bitrate - referenceBitrate else referenceBitrate - bitrate
        val relativeDifference = difference.toDouble() / referenceBitrate.toDouble()
        score +=
            when {
                relativeDifference <= 0.10 -> 12
                relativeDifference <= 0.25 -> 8
                relativeDifference <= 0.50 -> 4
                else -> 0
            }
    }
    return score
}

private fun resolutionBand(height: Int): Int =
    when {
        height >= 1600 -> 4
        height >= 1000 -> 3
        height >= 700 -> 2
        else -> 1
    }

private fun String?.sameOfflineFeature(other: String?): Boolean {
    val value = this?.normalizedOfflineFeature()?.takeIf(String::isNotEmpty) ?: return false
    return value == other?.normalizedOfflineFeature()
}

private fun String.normalizedOfflineFeature(): String = trim().lowercase()

private fun offlineNameSimilarity(
    candidate: String,
    reference: String,
): Double {
    if (candidate == reference) return 1.0
    val candidateTokens = candidate.offlineNameTokens()
    val referenceTokens = reference.offlineNameTokens()
    if (candidateTokens.isEmpty() || referenceTokens.isEmpty()) return 0.0
    val union = candidateTokens union referenceTokens
    return (candidateTokens intersect referenceTokens).size.toDouble() / union.size.toDouble()
}

private fun String.offlineNameTokens(): Set<String> =
    split(Regex("[^a-z0-9\\p{L}]+"))
        .asSequence()
        .filter(String::isNotBlank)
        .toSet()

private fun offlineAudioSimilarity(
    candidates: List<AudioTrackInfo>,
    references: List<AudioTrackInfo>,
): Int {
    if (candidates.isEmpty() || references.isEmpty()) return 0
    val referenceSignatures = references.map(AudioTrackInfo::offlineSignature).toSet()
    val candidateSignatures = candidates.map(AudioTrackInfo::offlineSignature).toSet()
    val exactMatches = (referenceSignatures intersect candidateSignatures).size
    val referenceLanguages = references.mapNotNull { it.language?.normalizedOfflineFeature() }.toSet()
    val candidateLanguages = candidates.mapNotNull { it.language?.normalizedOfflineFeature() }.toSet()
    return exactMatches * 12 + (referenceLanguages intersect candidateLanguages).size * 6
}

private fun AudioTrackInfo.offlineSignature(): String =
    listOf(
        language?.normalizedOfflineFeature().orEmpty(),
        codec?.normalizedOfflineFeature().orEmpty(),
        channelCount?.toString() ?: channels?.normalizedOfflineFeature().orEmpty(),
        default?.toString().orEmpty(),
    ).joinToString("|")

/** Match only compatible language/codec tracks, then preserve default/forced intent. */
internal fun matchOfflineSubtitleTrack(
    tracks: List<SubtitleTrackInfo>,
    language: String?,
    codec: String?,
    default: Boolean,
    forced: Boolean,
): SubtitleTrackInfo? {
    val normalizedLanguage = language?.normalizedOfflineFeature()?.takeIf(String::isNotEmpty)
        ?: return null
    val normalizedCodec = codec?.normalizedOfflineFeature()?.takeIf(String::isNotEmpty)
    return tracks
        .asSequence()
        .filter { it.index != null }
        .filter { track -> track.language?.normalizedOfflineFeature() == normalizedLanguage }
        .filter { track -> track.forced == forced }
        .maxByOrNull { track ->
            (if (track.codec?.normalizedOfflineFeature() == normalizedCodec) 4 else 0) +
                (if (track.default == default) 2 else 0) +
                (if (!track.external) 1 else 0)
        }
}

/** Sum the actual per-episode choices; unknown metadata keeps the estimate unknown. */
fun estimateOfflineDownloadBytes(
    currentItemId: String,
    currentTitle: String,
    currentRuntimeMinutes: Int?,
    currentVersions: List<MediaVersion>,
    seasonEpisodes: List<Episode>,
    selection: OfflineDownloadSelection,
): Long? {
    var total = 0L
    buildOfflineDownloadRequests(
        serverId = "",
        currentItemId = currentItemId,
        currentTitle = currentTitle,
        currentRuntimeMinutes = currentRuntimeMinutes,
        currentVersions = currentVersions,
        seasonEpisodes = seasonEpisodes,
        selection = selection,
    ).forEach { request ->
        total = total.saturatingAdd(request.estimatedBytes ?: return null)
    }
    return total
}

internal data class OfflineQueueSummary(
    val total: Int,
    val active: Int,
    val paused: Int,
    val completed: Int,
    val failed: Int,
    val retryScheduled: Int,
    val completedBytes: Long,
)

internal fun summarizeOfflineQueue(items: List<OfflineMedia>): OfflineQueueSummary =
    OfflineQueueSummary(
        total = items.size,
        active =
            items.count {
                it.status in
                    setOf(
                        DownloadStatus.Queued,
                        DownloadStatus.WaitingForWifi,
                        DownloadStatus.Downloading,
                    )
            },
        paused = items.count { it.status == DownloadStatus.Paused },
        completed = items.count { it.status == DownloadStatus.Completed },
        failed = items.count { it.status == DownloadStatus.Failed },
        retryScheduled =
            items.count {
                it.status == DownloadStatus.Queued && it.nextRetryAt > 0L
            },
        completedBytes =
            items
                .asSequence()
                .filter { it.status == DownloadStatus.Completed }
                .sumOf(OfflineMedia::downloadedBytes),
    )

interface OfflineMediaManager {
    val items: StateFlow<List<OfflineMedia>>
    val wifiOnly: StateFlow<Boolean>
    val policy: StateFlow<OfflineDownloadPolicy>

    fun enqueue(request: OfflineDownloadRequest)

    fun pause(id: String)

    fun pauseAll()

    fun resume(id: String)

    fun resumeAll()

    fun remove(id: String)

    fun setWifiOnly(value: Boolean)

    fun setMaxConcurrentDownloads(value: Int)

    fun setAutoDeleteWatched(value: Boolean)

    fun onPlaybackCompleted(
        serverId: String,
        itemId: String,
    )
}

internal const val OFFLINE_POLICY_KEY = "offline.media.policy.v2"

internal fun loadOfflineDownloadPolicy(settings: Settings): OfflineDownloadPolicy {
    val fallback = OfflineDownloadPolicy(wifiOnly = settings.getBoolean("offline.media.wifiOnly", true))
    val stored = settings.getStringOrNull(OFFLINE_POLICY_KEY) ?: return fallback
    return runCatching {
        kotlinx.serialization.json.Json
            .decodeFromString(
                OfflineDownloadPolicy.serializer(),
                stored,
            ).normalized()
    }.getOrDefault(fallback)
}

internal fun persistOfflineDownloadPolicy(
    settings: Settings,
    policy: OfflineDownloadPolicy,
): OfflineDownloadPolicy {
    val normalized = policy.normalized()
    settings.putBoolean("offline.media.wifiOnly", normalized.wifiOnly)
    settings.putString(
        OFFLINE_POLICY_KEY,
        kotlinx.serialization.json.Json
            .encodeToString(OfflineDownloadPolicy.serializer(), normalized),
    )
    return normalized
}

expect fun createOfflineMediaManager(
    settings: Settings,
    registry: ServerRegistry,
): OfflineMediaManager
