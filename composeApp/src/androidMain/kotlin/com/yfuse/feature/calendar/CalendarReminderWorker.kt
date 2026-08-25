package com.yfuse.feature.calendar

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.russhwolf.settings.Settings
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.scheduledEpochMillis
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "yfuse.calendar.reminders.v1"
private const val CHANNEL_ID = "airing_calendar"

fun scheduleCalendarReminderWork(context: Context) {
    val request =
        PeriodicWorkRequest
            .Builder(CalendarReminderWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            ).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
}

private const val BROADCAST_LATE_WINDOW_MS = 90 * 60_000L
private const val REMINDER_WORK_DEADLINE_MS = 30_000L

private fun availableSeenKey(
    tmdbId: Int,
    entry: com.yfuse.core.model.CalendarEntry,
): String =
    "calendar.reminder.available.seen.$tmdbId." +
        "${entry.episode.seasonNumber}.${entry.episode.episodeNumber}"

class CalendarReminderWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val koin = runCatching { GlobalContext.get() }.getOrElse { return Result.retry() }
        val follows = koin.get<CalendarFollowStore>().followed.value
        if (follows.isEmpty() || !notificationsAllowed()) return Result.success()
        val calendarResult =
            withTimeoutOrNull(REMINDER_WORK_DEADLINE_MS) {
                koin.get<AiringCalendarRepository>()
                    .followedCalendar(pastDays = 1, futureDays = 2)
            } ?: return Result.retry()
        val days = calendarResult.getOrElse { return Result.retry() }
        val settings = koin.get<Settings>()
        val now = currentEpochMillis()
        follows.forEach { followed ->
            val entries = days.flatMap { it.entries }.filter { it.episode.showTmdbId == followed.tmdbId }
            val available = entries.filter { it.status in setOf(LibraryStatus.Available, LibraryStatus.InProgress) }
            if (followed.reminderMode == CalendarReminderMode.WhenAvailable) {
                val baselineKey = "calendar.reminder.available.baseline.${followed.tmdbId}"
                if (!settings.getBoolean(baselineKey, false)) {
                    // Following an existing show must not announce its entire historical
                    // library as newly downloaded. The first successful observation is a
                    // baseline; only later transitions produce notifications.
                    available.forEach { entry ->
                        settings.putBoolean(availableSeenKey(followed.tmdbId, entry), true)
                    }
                    settings.putBoolean(baselineKey, true)
                } else {
                    val newlyAvailable =
                        available.filterNot { entry ->
                            settings.getBoolean(availableSeenKey(followed.tmdbId, entry), false)
                        }
                    if (newlyAvailable.isNotEmpty()) {
                        val coordinates =
                            newlyAvailable
                                .joinToString("|") {
                                    "${it.episode.seasonNumber}:${it.episode.episodeNumber}"
                                }
                        notifyOnce(
                            settings = settings,
                            key = "available.${followed.tmdbId}.${coordinates.hashCode()}",
                            title = "${followed.title} 已入库",
                            text = newlyAvailable.joinToString("、") { it.episode.episodeLabel },
                        )
                        newlyAvailable.forEach { entry ->
                            settings.putBoolean(availableSeenKey(followed.tmdbId, entry), true)
                        }
                    }
                }
            }
            if (followed.reminderMode in setOf(CalendarReminderMode.AtBroadcast, CalendarReminderMode.BeforeAndAtBroadcast)) {
                entries.groupBy { it.episode.airDate to it.episode.airTime }.forEach { (_, sameSlot) ->
                    val sample = sameSlot.first().episode
                    val time = sample.airTime ?: return@forEach
                    val zone = sample.timeZoneId ?: return@forEach
                    val at = scheduledEpochMillis(sample.airDate, time, zone) ?: return@forEach
                    val delta = at - now
                    val beforeWindow = followed.remindBeforeMinutes * 60_000L
                    if (
                        followed.reminderMode == CalendarReminderMode.BeforeAndAtBroadcast &&
                        delta in 0..beforeWindow
                    ) {
                        notifyOnce(
                            settings,
                            "before.${followed.tmdbId}.${sample.airDate}.$time." +
                                sameSlot.joinToString("-") {
                                    "${it.episode.seasonNumber}e${it.episode.episodeNumber}"
                                },
                            "${followed.title} 即将更新",
                            "${sameSlot.joinToString("、") { it.episode.episodeLabel }} · $time",
                        )
                    }
                    if (delta in -BROADCAST_LATE_WINDOW_MS..0L) {
                        notifyOnce(
                            settings,
                            "air.${followed.tmdbId}.${sample.airDate}.$time." +
                                sameSlot.joinToString("-") {
                                    "${it.episode.seasonNumber}e${it.episode.episodeNumber}"
                                },
                            "${followed.title} 已更新",
                            sameSlot.joinToString("、") { it.episode.episodeLabel },
                        )
                    }
                }
            }
        }
        return Result.success()
    }

    private fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notifyOnce(
        settings: Settings,
        key: String,
        title: String,
        text: String,
    ) {
        val settingKey = "calendar.reminder.sent.$key"
        if (settings.getBoolean(settingKey, false)) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launch = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending =
            launch?.let {
                PendingIntent.getActivity(
                    applicationContext,
                    key.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        manager.notify(
            key.hashCode(),
            NotificationCompat
                .Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build(),
        )
        settings.putBoolean(settingKey, true)
    }
}
