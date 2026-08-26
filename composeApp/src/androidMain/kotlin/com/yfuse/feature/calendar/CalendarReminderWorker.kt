package com.yfuse.feature.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.russhwolf.settings.Settings
import com.yfuse.R
import com.yfuse.core.data.AiringCalendarRepository
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.core.model.LibraryStatus
import com.yfuse.core.util.currentEpochMillis
import com.yfuse.core.util.scheduledEpochMillis
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

private const val WORK_NAME = "yfuse.calendar.reminders.v1"
private const val IMMEDIATE_WORK_NAME = "yfuse.calendar.reminders.immediate.v1"
private const val NEXT_ALARM_ACTION = "com.yfuse.calendar.NEXT_REMINDER"
private const val NEXT_ALARM_REQUEST_CODE = 4104
private const val CHANNEL_ID = "airing_calendar"

fun scheduleCalendarReminderWork(context: Context) {
    val request =
        PeriodicWorkRequest
            .Builder(CalendarReminderWorker::class.java, 15, TimeUnit.MINUTES)
            // Broadcast reminders can be evaluated from the verified on-device schedule
            // cache. Requiring a network hid notifications whenever the device was offline.
            .build()
    val workManager = WorkManager.getInstance(context)
    workManager.enqueueUniquePeriodicWork(
        WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
    )
    // Populate caches and arm the nearest exact alarm immediately after app/process start.
    workManager.enqueueUniqueWork(
        IMMEDIATE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        OneTimeWorkRequest.Builder(CalendarReminderWorker::class.java).build(),
    )
}

class CalendarReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            NEXT_ALARM_ACTION ->
                WorkManager.getInstance(context).enqueueUniqueWork(
                    IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequest.Builder(CalendarReminderWorker::class.java).build(),
                )
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> scheduleCalendarReminderWork(context)
        }
    }
}

private fun scheduleNextCalendarAlarm(
    context: Context,
    triggerAtEpochMs: Long?,
) {
    val manager = context.getSystemService(AlarmManager::class.java)
    val intent = Intent(context, CalendarReminderAlarmReceiver::class.java).setAction(NEXT_ALARM_ACTION)
    val pending =
        PendingIntent.getBroadcast(
            context,
            NEXT_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    if (triggerAtEpochMs == null) {
        manager.cancel(pending)
        return
    }
    when {
        Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms() ->
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pending)
        Build.VERSION.SDK_INT >= 31 ->
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pending)
        Build.VERSION.SDK_INT >= 23 ->
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pending)
        else ->
            manager.setExact(AlarmManager.RTC_WAKEUP, triggerAtEpochMs, pending)
    }
}

private const val BROADCAST_LATE_WINDOW_MS = 90 * 60_000L
private const val REMINDER_WORK_DEADLINE_MS = 30_000L
private const val MAX_REMINDER_DEDUP_KEYS = 1_000

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
        if (follows.isEmpty()) {
            scheduleNextCalendarAlarm(applicationContext, null)
            return Result.success()
        }
        if (!notificationsAllowed()) {
            scheduleNextCalendarAlarm(applicationContext, null)
            return Result.success()
        }
        val repository = koin.get<AiringCalendarRepository>()
        val calendarResult =
            withTimeoutOrNull(REMINDER_WORK_DEADLINE_MS) {
                repository.followedCalendar(pastDays = 1, futureDays = 2)
            } ?: return Result.retry()
        val days = calendarResult.getOrElse { return Result.retry() }
        val settings = koin.get<Settings>()
        pruneReminderDedupKeys(settings)
        val now = currentEpochMillis()
        val nextWakeCandidates = mutableListOf<Long>()
        val followedByTmdb = follows.associateBy { it.tmdbId }
        repository.scheduleChanges().forEach { change ->
            val followed = followedByTmdb[change.tmdbId] ?: return@forEach
            notifyOnce(
                settings = settings,
                key = "schedule-change.${change.tmdbId}.${change.revision}.${change.message.hashCode()}",
                title = "${change.title} 排期有调整",
                text = change.message,
                followed = followed,
            )
        }
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
                            followed = followed,
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
                    if (at > now + 5_000L) nextWakeCandidates += at
                    if (
                        followed.reminderMode == CalendarReminderMode.BeforeAndAtBroadcast &&
                        at - beforeWindow > now + 5_000L
                    ) {
                        nextWakeCandidates += at - beforeWindow
                    }
                    if (
                        followed.reminderMode == CalendarReminderMode.BeforeAndAtBroadcast &&
                        delta in 1L..beforeWindow
                    ) {
                        notifyOnce(
                            settings,
                            "before.${followed.tmdbId}.${sample.airDate}.$time." +
                                sameSlot.joinToString("-") {
                                    "${it.episode.seasonNumber}e${it.episode.episodeNumber}"
                                },
                            "${followed.title} 即将更新",
                            "${sameSlot.joinToString("、") { it.episode.episodeLabel }} · $time",
                            followed = followed,
                        )
                    }
                    if (delta in -BROADCAST_LATE_WINDOW_MS..0L) {
                        notifyOnce(
                            settings,
                            "air.${followed.tmdbId}.${sample.airDate}.$time." +
                                sameSlot.joinToString("-") {
                                    "${it.episode.seasonNumber}e${it.episode.episodeNumber}"
                                },
                            "${followed.title} 已播出",
                            sameSlot.joinToString("、") { it.episode.episodeLabel },
                            followed = followed,
                        )
                    }
                }
            }
        }
        scheduleNextCalendarAlarm(applicationContext, nextWakeCandidates.minOrNull())
        return Result.success()
    }

    private fun pruneReminderDedupKeys(settings: Settings) {
        val keys = settings.keys.filter { it.startsWith("calendar.reminder.sent.") }.sorted()
        keys.take((keys.size - MAX_REMINDER_DEDUP_KEYS).coerceAtLeast(0)).forEach(settings::remove)
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
        followed: FollowedSeries? = null,
    ) {
        val settingKey = "calendar.reminder.sent.$key"
        if (settings.getBoolean(settingKey, false)) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val launch =
            applicationContext.packageManager
                .getLaunchIntentForPackage(applicationContext.packageName)
                ?.apply {
                    followed?.seriesItemId?.let {
                        putExtra("calendar_series_item_id", it)
                        putExtra("calendar_server_id", followed.serverId)
                    }
                }
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
                .setSmallIcon(R.drawable.ic_notification_calendar)
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
