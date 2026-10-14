package com.yfuse.core.offline

import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.redactDiagnosticText

internal const val OFFLINE_PROGRESS_CHECKPOINT_BYTES = 8L * 1024L * 1024L

internal fun shouldPersistOfflineProgress(
    lastCheckpointBytes: Long,
    downloadedBytes: Long,
): Boolean = downloadedBytes - lastCheckpointBytes >= OFFLINE_PROGRESS_CHECKPOINT_BYTES

internal fun sameOfflineMediaSource(
    itemId: String,
    first: String?,
    second: String?,
): Boolean = (first ?: itemId) == (second ?: itemId)

internal data class OfflineEnqueuePlan(
    val item: OfflineMedia,
    val sourceChanged: Boolean,
)

internal fun planOfflineEnqueue(
    old: OfflineMedia?,
    request: OfflineDownloadRequest,
    nowMs: Long,
): OfflineEnqueuePlan {
    val sourceChanged =
        old != null &&
            !sameOfflineDownloadVariant(
                itemId = request.itemId,
                oldSourceId = old.mediaSourceId,
                newSourceId = request.mediaSourceId,
                oldQuality = old.quality,
                newQuality = request.quality,
                oldSubtitleIndex = old.subtitleStreamIndex,
                newSubtitleIndex = request.subtitleStreamIndex,
            )
    val nextRevision =
        old?.downloadRevision?.let {
            if (it == Long.MAX_VALUE) 0L else it + 1L
        } ?: 1L
    return OfflineEnqueuePlan(
        item =
            OfflineMedia(
                id = "${request.serverId}#${request.itemId}",
                serverId = request.serverId,
                itemId = request.itemId,
                title = request.title,
                mediaSourceId = request.mediaSourceId,
                quality = request.quality,
                subtitleStreamIndex = request.subtitleStreamIndex,
                subtitleCodec = request.subtitleCodec,
                subtitleLanguage = request.subtitleLanguage,
                seriesId = request.seriesId,
                seasonId = request.seasonId,
                automaticallyDownloaded = request.automaticallyDownloaded,
                legacySourceUrl = null,
                posterUrl = null,
                localPath = old?.localPath.takeUnless { sourceChanged },
                subtitlePath = old?.subtitlePath.takeUnless { sourceChanged },
                downloadedBytes = old?.downloadedBytes?.takeUnless { sourceChanged } ?: 0L,
                totalBytes =
                    old?.totalBytes?.takeUnless { sourceChanged }
                        ?: request.estimatedBytes?.coerceAtLeast(0L)
                        ?: 0L,
                downloadRevision = nextRevision,
                resumeValidator = old?.resumeValidator.takeUnless { sourceChanged },
                status =
                    if (!sourceChanged && old?.playable == true) {
                        DownloadStatus.Completed
                    } else {
                        DownloadStatus.Queued
                    },
                updatedAtEpochMs = nowMs,
                addedOrder = old?.addedOrder ?: 0L,
            ),
        sourceChanged = sourceChanged,
    )
}

actual fun createOfflineMediaManager(
    settings: Settings,
    registry: ServerRegistry,
    repository: EmbyRepository,
    userAgent: () -> String,
): OfflineMediaManager =
    AndroidOfflineMediaManager(
        context = offlineApplicationContext,
        settings = settings,
        registry = registry,
        repository = repository,
        userAgent = userAgent,
    )

internal fun sanitizeLegacyOfflineItem(item: OfflineMedia): OfflineMedia =
    item.copy(
        mediaSourceId = item.mediaSourceId ?: item.legacySourceUrl.queryParameter("MediaSourceId"),
        legacySourceUrl = null,
        // The download UI never consumes this field. Dropping it also removes legacy api_key
        // query parameters from the persisted index.
        posterUrl = null,
        error = item.error?.let(::redactDiagnosticText),
    )

internal fun Long.nextOfflineRevision(): Long = if (this == Long.MAX_VALUE) 0L else this + 1L
