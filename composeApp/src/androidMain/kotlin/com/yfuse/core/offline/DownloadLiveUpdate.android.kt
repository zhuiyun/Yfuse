package com.yfuse.core.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresApi
import com.yfuse.core.notification.LiveUpdateColors
import com.yfuse.core.notification.requestPromotedOngoing

/**
 * The downloads the live update is about. Process-wide because the worker, the service and the
 * attention notification all draw the same queue; see [nextDownloadLiveBatch].
 */
private object DownloadLiveBatch {
    private var members = emptyList<String>()

    @Synchronized
    fun of(items: List<OfflineMedia>): List<OfflineMedia> {
        members = nextDownloadLiveBatch(members, items)
        val byId = items.associateBy(OfflineMedia::id)
        return members.mapNotNull(byId::get)
    }
}

/**
 * 实况通知 for downloads: one segment per episode — finished, downloading, waiting — with 暂停 and
 * 停止 while it runs and 继续 once it has stopped. Promotion is asked for only while something is
 * actually downloading; a paused queue keeps an ordinary notification. Null when there is no batch.
 */
@RequiresApi(36)
internal fun downloadLiveUpdate(
    context: Context,
    channelId: String,
    items: List<OfflineMedia>,
    contentIntent: PendingIntent,
): Notification? {
    val batch = DownloadLiveBatch.of(items)
    val progress = downloadLiveProgress(batch) ?: return null
    val summary = summarizeDownloads(items)
    val running = summary.active > 0
    val current = progress.downloading.firstOrNull()
    val title =
        when {
            progress.total == 1 -> batch.single().title
            running -> "正在下载 · ${progress.done} / ${progress.total}"
            summary.failed > 0 && summary.paused == 0 -> summary.title
            else -> "已暂停 · ${progress.done} / ${progress.total}"
        }
    val text =
        when {
            current != null && progress.total == 1 -> "${(current.progress * 100).toInt()}%"
            current != null -> "${current.title} · ${(current.progress * 100).toInt()}%"
            else -> summary.detail
        }
    val style =
        Notification
            .ProgressStyle()
            .setProgressSegments(
                progress.segments.map { segment ->
                    Notification.ProgressStyle.Segment(segment.length).also {
                        if (segment.state == DownloadLiveState.Failed) it.setColor(LiveUpdateColors.FAILED)
                    }
                },
            ).setProgress(progress.progress)
    val builder =
        Notification
            .Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(running)
            .setStyle(style)
    if (running) {
        builder
            .setShortCriticalText("${progress.percent}%")
            .requestPromotedOngoing()
        builder.addAction(downloadAction(context, DownloadNotificationActions.ACTION_PAUSE, "暂停", 2413))
        builder.addAction(downloadAction(context, DownloadNotificationActions.ACTION_STOP, "停止", 2416))
    } else if (summary.paused + summary.failed > 0) {
        val label = if (summary.failed > 0) "继续 / 重试" else "继续"
        builder.addAction(downloadAction(context, DownloadNotificationActions.ACTION_RESUME, label, 2414))
    }
    return builder.build()
}

private fun downloadAction(
    context: Context,
    action: String,
    label: String,
    requestCode: Int,
): Notification.Action {
    val pending =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, DownloadNotificationActions::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    return Notification.Action.Builder(null, label, pending).build()
}
