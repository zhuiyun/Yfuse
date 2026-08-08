package com.yfuse.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.yfuse.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Carries the update download so it keeps running once the dialog is dismissed, the user moves
 * on through the app, or Yfuse goes to the background entirely.
 *
 * The transfer itself lives in [AppUpdateManager] — which is application-scoped and holds the
 * resumable state — so neither this service stopping nor the process dying loses the bytes
 * already written.
 */
class UpdateDownloadService : Service() {
    private companion object {
        const val CHANNEL_ID = "yfuse_app_update"
        const val NOTIFICATION_ID = 2411
        const val PROGRESS_NOTIFICATION_INTERVAL_MS = 700L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager: AppUpdateManager by lazy { GlobalContext.get().get() }
    private var progress: Job? = null

    /**
     * Outstanding transfer attempts. The service stops only once every start it was given has
     * been served, so a resume arriving while the previous transfer winds down is never
     * answered by a service on its way out.
     */
    private var pending = 0
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        startForeground(NOTIFICATION_ID, notification("正在准备下载新版本", 0f, indeterminate = true))
        startProgressUpdates()
        pending += 1
        // The scope is Main.immediate, so `pending` is only ever touched from the main thread.
        scope.launch {
            try {
                manager.runActiveDownload()
            } finally {
                pending -= 1
                if (pending == 0) {
                    progress?.cancel()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // Honours only this start: a newer one keeps the service alive.
                    stopSelf(lastStartId)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProgressUpdates() {
        if (progress?.isActive == true) return
        progress = scope.launch {
            var lastPostedAtMs = 0L
            manager.state.collectLatest { state ->
                if (state !is UpdateState.Downloading) return@collectLatest
                val now = System.currentTimeMillis()
                if (now - lastPostedAtMs < PROGRESS_NOTIFICATION_INTERVAL_MS) return@collectLatest
                lastPostedAtMs = now
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(
                        "正在下载 ${state.manifest.versionName}",
                        state.progress,
                        indeterminate = false,
                    ),
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(
        title: String,
        progress: Float,
        indeterminate: Boolean,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val percent = (progress * 100f).toInt().coerceIn(0, 100)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "正在连接升级服务器" else "$percent%")
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent, indeterminate)
            .build()
    }
}
