package com.yfuse.app

import com.yfuse.core.offline.DownloadStatus
import com.yfuse.core.offline.OfflineMedia

/** How long the activity capsule holds a result before folding back to what is still going on. */
internal const val CAPSULE_FLASH_MS = 2_000L

/**
 * The download that has just finished, by comparing the queue with how it stood before. Only a
 * download seen running counts: the first look at the queue, and a finished one that reappears
 * after a restart, say nothing.
 */
internal fun completedDownloadFlash(
    before: Map<String, DownloadStatus>,
    after: List<OfflineMedia>,
): String? {
    val finished =
        after.filter { item ->
            val previous = before[item.id]
            item.status == DownloadStatus.Completed && previous != null && previous != DownloadStatus.Completed
        }
    return when (finished.size) {
        0 -> null
        1 -> "已下载 · ${finished.single().title}"
        else -> "已下载 ${finished.size} 项"
    }
}

/** What the capsule says when a cast session it was showing ends. */
internal fun castEndedFlash(device: String?): String = if (device.isNullOrBlank()) "投屏已结束" else "投屏已结束 · $device"
