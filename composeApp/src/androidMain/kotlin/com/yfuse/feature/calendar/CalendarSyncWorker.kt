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

/** The screen's default window (`AiringCalendarRepository.calendar`), so the rows it primes are the rows read. */
private const val CALENDAR_SYNC_PAST_DAYS = 7
private const val CALENDAR_SYNC_FUTURE_DAYS = 14

/**
 * Resolves the calendar window ahead of the screen being opened.
 *
 * It asks for the same window the screen and the home card read, so its resolved rows are
 * exactly the ones the next foreground launch paints from SQLite. A wider window costs a
 * full multi-server availability pass over rows nobody reads until they scroll into the
 * default window anyway, which the screen resolves on its own when they do.
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
                pastDays = CALENDAR_SYNC_PAST_DAYS,
                futureDays = CALENDAR_SYNC_FUTURE_DAYS,
                forceRefresh = false,
            ).fold(
                onSuccess = { Result.success() },
                onFailure = {
                    if (runAttemptCount < 3) Result.retry() else Result.failure()
                },
            )
    }
}
