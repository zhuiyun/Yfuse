package com.yfuse.feature.player

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.yfuse.core.logging.AppLog
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

internal const val PLAYBACK_OUTBOX_WORK_NAME = "yfuse.playback.outbox.flush.v1"
internal val PLAYBACK_OUTBOX_WORK_POLICY: ExistingWorkPolicy = ExistingWorkPolicy.KEEP

internal fun playbackOutboxFlushRequest(): OneTimeWorkRequest =
    OneTimeWorkRequest
        .Builder(PlaybackOutboxFlushWorker::class.java)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).setBackoffCriteria(
            BackoffPolicy.EXPONENTIAL,
            WorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS,
        ).addTag(PLAYBACK_OUTBOX_WORK_NAME)
        .build()

internal actual fun schedulePlaybackOutboxFlush() {
    val context = androidAppContext ?: return
    WorkManager.getInstance(context).enqueueUniqueWork(
        PLAYBACK_OUTBOX_WORK_NAME,
        PLAYBACK_OUTBOX_WORK_POLICY,
        playbackOutboxFlushRequest(),
    )
}

/**
 * A single WorkManager attempt is deliberately bounded. Queue-level retry timestamps and the
 * WorkManager exponential backoff cooperate without keeping a process alive in a delay loop.
 */
class PlaybackOutboxFlushWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val coordinator =
            runCatching {
                GlobalContext.get().get<PlaybackReportingCoordinator>()
            }.getOrElse { error ->
                AppLog.error(
                    category = "playback.outbox",
                    event = "worker_dependency_failed",
                    message = "Playback outbox worker could not resolve its coordinator",
                    throwable = error,
                )
                return Result.retry()
            }
        return try {
            val summary = coordinator.flushPendingOnce()
            if (summary.hasRetryablePending) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            AppLog.error(
                category = "playback.outbox",
                event = "worker_failed",
                message = "Playback outbox background flush failed",
                throwable = error,
            )
            Result.retry()
        }
    }
}
