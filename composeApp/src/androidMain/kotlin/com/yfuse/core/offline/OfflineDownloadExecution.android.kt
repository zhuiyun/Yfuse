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
    val contentIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val progress =
        if (total > 0L) {
            ((downloaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
    return Notification
        .Builder(context, OFFLINE_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(if (total > 0L) "$progress%" else "正在连接服务器")
        .setContentIntent(contentIntent)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(100, progress, total <= 0L)
        .build()
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
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        return try {
            setForeground(offlineForegroundInfo(applicationContext))
            manager.refreshAutoDownloads()
            coroutineScope {
                val updates =
                    launch {
                        manager.items.collectLatest { items ->
                            val active = items.firstOrNull { it.status == DownloadStatus.Downloading }
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
                            val active = items.firstOrNull { it.status == DownloadStatus.Downloading }
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
                    manager.rebuildWakeSchedule(ExistingWorkPolicy.REPLACE)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
