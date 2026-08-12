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
    val delay = (OFFLINE_RETRY_BASE_DELAY_MS shl exponent)
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
    /** Read-once compatibility with v1 indexes; sanitized to null immediately after loading. */
    @SerialName("sourceUrl") val legacySourceUrl: String? = null,
    val posterUrl: String? = null,
    val localPath: String? = null,
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
        get() = if (totalBytes > 0L) {
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
)

internal data class OfflineQueueSummary(
    val total: Int,
    val active: Int,
    val paused: Int,
    val completed: Int,
    val failed: Int,
    val retryScheduled: Int,
    val completedBytes: Long,
)

internal fun summarizeOfflineQueue(items: List<OfflineMedia>): OfflineQueueSummary = OfflineQueueSummary(
    total = items.size,
    active = items.count {
        it.status in setOf(
            DownloadStatus.Queued,
            DownloadStatus.WaitingForWifi,
            DownloadStatus.Downloading,
        )
    },
    paused = items.count { it.status == DownloadStatus.Paused },
    completed = items.count { it.status == DownloadStatus.Completed },
    failed = items.count { it.status == DownloadStatus.Failed },
    retryScheduled = items.count {
        it.status == DownloadStatus.Queued && it.nextRetryAt > 0L
    },
    completedBytes = items.asSequence()
        .filter { it.status == DownloadStatus.Completed }
        .sumOf(OfflineMedia::downloadedBytes),
)

interface OfflineMediaManager {
    val items: StateFlow<List<OfflineMedia>>
    val wifiOnly: StateFlow<Boolean>

    fun enqueue(request: OfflineDownloadRequest)
    fun pause(id: String)
    fun pauseAll()
    fun resume(id: String)
    fun resumeAll()
    fun remove(id: String)
    fun setWifiOnly(value: Boolean)
}

expect fun createOfflineMediaManager(
    settings: Settings,
    registry: ServerRegistry,
): OfflineMediaManager
