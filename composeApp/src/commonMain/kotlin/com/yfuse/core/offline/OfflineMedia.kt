package com.yfuse.core.offline

import com.russhwolf.settings.Settings
import com.yfuse.core.data.ServerRegistry
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
    currentVersions: List<com.yfuse.core.model.MediaVersion>,
    seasonEpisodes: List<com.yfuse.core.model.Episode>,
    selection: OfflineDownloadSelection,
): List<OfflineDownloadRequest> {
    val episodeIds =
        selectOfflineBatchItems(
            mode = selection.batchMode,
            currentItemId = currentItemId,
            seasonItems = seasonEpisodes.map { OfflineBatchItem(it.id, it.played) },
        )
    return episodeIds.map { itemId ->
        val episode = seasonEpisodes.firstOrNull { it.id == itemId }
        val versions = if (itemId == currentItemId) currentVersions else episode?.versions.orEmpty()
        val selectedVersion =
            versions.firstOrNull { it.id == selection.mediaSourceId }
                ?: versions.firstOrNull()
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
            // A track index belongs to one MediaSource. Siblings may not have the same
            // subtitle layout, so only the explicitly inspected current item carries it.
            subtitleStreamIndex = selection.subtitleStreamIndex.takeIf { itemId == currentItemId },
            subtitleCodec = selection.subtitleCodec.takeIf { itemId == currentItemId },
            subtitleLanguage = selection.subtitleLanguage.takeIf { itemId == currentItemId },
            estimatedBytes =
                estimateOfflineBytes(
                    sourceSizeBytes = selectedVersion?.sizeBytes,
                    sourceBitrateBps = selectedVersion?.bitrateBps,
                    runtimeMinutes = episode?.runtimeMinutes ?: currentRuntimeMinutes,
                    quality = selection.quality,
                    includeSubtitle = itemId == currentItemId && selection.subtitleStreamIndex != null,
                ),
        )
    }
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
