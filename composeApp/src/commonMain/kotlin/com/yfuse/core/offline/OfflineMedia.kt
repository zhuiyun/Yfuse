package com.yfuse.core.offline

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.StateFlow
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
data class OfflineMedia(
    val id: String,
    val serverId: String,
    val itemId: String,
    val title: String,
    val sourceUrl: String,
    val posterUrl: String? = null,
    val localPath: String? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.Queued,
    val error: String? = null,
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
    val sourceUrl: String,
    val posterUrl: String? = null,
)

interface OfflineMediaManager {
    val items: StateFlow<List<OfflineMedia>>
    val wifiOnly: StateFlow<Boolean>

    fun enqueue(request: OfflineDownloadRequest)
    fun pause(id: String)
    fun resume(id: String)
    fun remove(id: String)
    fun setWifiOnly(value: Boolean)
}

expect fun createOfflineMediaManager(settings: Settings): OfflineMediaManager
