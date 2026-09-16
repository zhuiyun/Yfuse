package com.yfuse.core.offline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yfuse.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

/** Keep the receiver alive until WorkManager has durably accepted the action. */
class DownloadNotificationActions : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val action = intent.action
        if (action != ACTION_PAUSE && action != ACTION_RESUME) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WorkManager
                    .getInstance(context)
                    .enqueueUniqueWork(
                        "download-notification-actions",
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<DownloadNotificationActionWorker>()
                            .setInputData(workDataOf("action" to action))
                            .build(),
                    ).result
                    .get(8, TimeUnit.SECONDS)
            } catch (error: Exception) {
                AppLog.warning(
                    "offline",
                    "notification_action_enqueue_failed",
                    "Could not enqueue download action",
                    error,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.yfuse.download.PAUSE"
        const val ACTION_RESUME = "com.yfuse.download.RESUME"
        const val EXTRA_OPEN_DOWNLOADS = "com.yfuse.OPEN_DOWNLOADS"
    }
}

class DownloadNotificationActionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        try {
            val manager = GlobalContext.get().get<OfflineMediaManager>() as AndroidOfflineMediaManager
            manager.applyNotificationAction(inputData.getString("action"))
            updateOfflineAttentionNotification(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.warning("offline", "notification_action_failed", "Download action failed", error)
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
}
