package com.yfuse.feature.calendar

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yfuse.core.data.AiringCalendarRepository
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

private const val CALENDAR_SYNC_WORK_NAME = "yfuse.calendar.local-index.v1"

/**
 * Refreshes followed/active titles independently from opening the calendar screen.
 *
 * The repository writes only changed event/resource rows into SQLite, so this work keeps the
 * next foreground launch instant without doing global discovery in the background.
 */
fun scheduleCalendarSyncWork(context: Context) {
    val request =
        PeriodicWorkRequest
            .Builder(CalendarSyncWorker::class.java, 30, TimeUnit.MINUTES)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        CALENDAR_SYNC_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

class CalendarSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository =
            runCatching { GlobalContext.get().get<AiringCalendarRepository>() }
                .getOrElse { return Result.retry() }
        return repository
            .homeCalendar(
                pastDays = 7,
                futureDays = 60,
                forceRefresh = false,
            ).fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                },
            )
    }
}
