package com.yfuse.core.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yfuse.MainActivity
import com.yfuse.core.data.isServerSessionRestoreFailure
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

private const val OFFLINE_NOTIFICATION_CHANNEL_ID = "yfuse_downloads"
private const val OFFLINE_SERVICE_NOTIFICATION_ID = 2410
private const val OFFLINE_WORK_NOTIFICATION_ID = 2411
private const val OFFLINE_ATTENTION_NOTIFICATION_ID = 2415

private fun ensureOfflineNotificationChannel(context: Context) {
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(
            OFFLINE_NOTIFICATION_CHANNEL_ID,
            "离线下载",
            NotificationManager.IMPORTANCE_LOW,
        ),
    )
}

private fun offlineDownloadNotification(
    context: Context,
    title: String,
    downloaded: Long,
    total: Long,
): Notification {
    val manager = runCatching { GlobalContext.get().get<OfflineMediaManager>() }.getOrNull()
    val summary = manager?.items?.value?.let(::summarizeDownloads)
    val contentIntent =
        PendingIntent.getActivity(
            context,
            2412,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(DownloadNotificationActions.EXTRA_OPEN_DOWNLOADS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val progress =
        if (summary != null) {
            summary.percent
        } else if (total > 0L) {
            (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
        } else {
            null
        }
    val builder =
        Notification
            .Builder(context, OFFLINE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(summary?.title ?: title)
            .setContentText(summary?.detail ?: "正在连接服务器")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(summary == null || summary.active > 0)
            .setProgress(100, progress ?: 0, progress == null && (summary == null || summary.active > 0))

    fun addAction(
        action: String,
        label: String,
        requestCode: Int,
    ) {
        val pending =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(context, DownloadNotificationActions::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        builder.addAction(Notification.Action.Builder(null, label, pending).build())
    }
    if (summary != null) {
        if (summary.active > 0) addAction(DownloadNotificationActions.ACTION_PAUSE, "全部暂停", 2413)
        if (summary.paused + summary.failed > 0) addAction(DownloadNotificationActions.ACTION_RESUME, "继续 / 重试", 2414)
    }
    return builder.build()
}

/** A separate non-foreground notification survives a paused or failed worker. */
internal fun updateOfflineAttentionNotification(context: Context) {
    val summary =
        GlobalContext
            .get()
            .get<OfflineMediaManager>()
            .items.value
            .let(::summarizeDownloads)
    val notifications = context.getSystemService(NotificationManager::class.java)
    if (summary.active > 0 || !summary.visible) {
        notifications.cancel(OFFLINE_ATTENTION_NOTIFICATION_ID)
    } else {
        ensureOfflineNotificationChannel(context)
        // Notifications may be disabled; the in-app queue always remains available.
        if (notifications.areNotificationsEnabled()) {
            notifications.notify(
                OFFLINE_ATTENTION_NOTIFICATION_ID,
                offlineDownloadNotification(context, summary.title, 0, 0),
            )
        }
    }
}

private fun offlineForegroundInfo(context: Context): ForegroundInfo {
    ensureOfflineNotificationChannel(context)
    val notification = offlineDownloadNotification(context, "准备下载", 0L, 0L)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(
            OFFLINE_WORK_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    } else {
        ForegroundInfo(OFFLINE_WORK_NOTIFICATION_ID, notification)
    }
}

class OfflineDownloadWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val manager =
            runCatching {
                GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
            }.getOrElse { error ->
                AppLog.error(
                    category = "offline",
                    event = "worker_dependency_failed",
                    message = "Offline worker could not resolve the shared download manager",
                    throwable = error,
                )
                return if (error.isServerSessionRestoreFailure() || runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
        return try {
            setForeground(offlineForegroundInfo(applicationContext))
            manager.refreshAutoDownloads()
            coroutineScope {
                val updates =
                    launch {
                        manager.items.collectLatest { items ->
                            updateOfflineAttentionNotification(applicationContext)
                            val active = items.firstOrNull { it.status != DownloadStatus.Completed }
                            if (active != null) {
                                applicationContext
                                    .getSystemService(NotificationManager::class.java)
                                    .notify(
                                        OFFLINE_WORK_NOTIFICATION_ID,
                                        offlineDownloadNotification(
                                            context = applicationContext,
                                            title = active.title,
                                            downloaded = active.downloadedBytes,
                                            total = active.totalBytes,
                                        ),
                                    )
                            }
                        }
                    }
                try {
                    manager.runPendingDownloads()
                } finally {
                    updates.cancel()
                    updateOfflineAttentionNotification(applicationContext)
                }
            }
            manager.rebuildWakeSchedule(
                policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                cancelWhenEmpty = false,
            )
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.error(
                category = "offline",
                event = "worker_failed",
                message = "Offline worker failed outside an individual download",
                throwable = error,
            )
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                manager.rebuildWakeSchedule(
                    policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                    cancelWhenEmpty = false,
                )
                Result.failure()
            }
        }
    }
}

class OfflineDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureOfflineNotificationChannel(this)
        startForeground(
            OFFLINE_SERVICE_NOTIFICATION_ID,
            offlineDownloadNotification(this, "准备下载", 0, 0),
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (work?.isActive == true) return START_NOT_STICKY
        val manager = GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
        work =
            scope.launch {
                val updates =
                    launch {
                        manager.items.collectLatest { items ->
                            updateOfflineAttentionNotification(applicationContext)
                            val active = items.firstOrNull { it.status != DownloadStatus.Completed }
                            if (active != null) {
                                getSystemService(NotificationManager::class.java).notify(
                                    OFFLINE_SERVICE_NOTIFICATION_ID,
                                    offlineDownloadNotification(
                                        context = this@OfflineDownloadService,
                                        title = active.title,
                                        downloaded = active.downloadedBytes,
                                        total = active.totalBytes,
                                    ),
                                )
                            }
                        }
                    }
                try {
                    manager.runPendingDownloads()
                } finally {
                    updates.cancel()
                    updateOfflineAttentionNotification(applicationContext)
                    manager.rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15 caps a dataSync service at six hours a day. Stopping cleanly here lets the
     * WorkManager wake-up reschedule the rest instead of the system killing the process.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        work?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
