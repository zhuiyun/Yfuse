package com.yfuse.feature.calendar

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.russhwolf.settings.SharedPreferencesSettings
import com.yfuse.core.data.CalendarFollowStore
import com.yfuse.core.data.CalendarReminderMode
import com.yfuse.core.data.FollowedSeries
import com.yfuse.launchMainActivityForInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CalendarReminderSchedulingInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workManager get() = WorkManager.getInstance(context)
    private val periodicName = "yfuse.calendar.reminders.v1"
    private val immediateName = "yfuse.calendar.reminders.immediate.v1"

    @Test(timeout = 90_000L)
    fun empty_and_automatic_follows_cancel_stale_work_and_manual_reminders_restore_it() {
        val preferences = context.getSharedPreferences("calendar-scheduler-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val follows = CalendarFollowStore(SharedPreferencesSettings(preferences))
        try {
            follows.follow(
                FollowedSeries(19_000_001, "Scheduler fixture", reminderMode = CalendarReminderMode.AtBroadcast),
            )
            scheduleCalendarReminderWork(context, follows)
            await("Enabled reminder has periodic work") { active(periodicName).isNotEmpty() }
            follows.unfollowAll()
            scheduleCalendarReminderWork(context, follows)
            await("Empty subscriptions remove existing work") { noReminderWork() }
            // The first fixture was explicitly dismissed; automatic discovery must
            // not restore it. Exercise enabling a different, newly discovered show.
            assertEquals(
                1,
                follows.autoFollowLibrarySeries(listOf(FollowedSeries(19_000_003, "Automatic fixture"))),
            )
            scheduleCalendarReminderWork(context, follows)
            await("Automatic discovery does not authorize reminders") { noReminderWork() }
            follows.setReminder(19_000_003, CalendarReminderMode.AtBroadcast)
            assertEquals(1, follows.notificationSubscriptions().size)
            scheduleCalendarReminderWork(context, follows)
            await("Explicit subscription restores work") { active(periodicName).size == 1 }
            follows.setReminder(19_000_003, CalendarReminderMode.Off)
            scheduleCalendarReminderWork(context, follows)
            await("Disabling the final reminder cancels work") { noReminderWork() }
        } finally {
            follows.unfollowAll()
            scheduleCalendarReminderWork(context, follows)
            preferences.edit().clear().commit()
        }
    }

    @Test(timeout = 90_000L)
    fun visible_activity_reacts_to_subscription_changes_on_every_supported_android_version() {
        val follows = GlobalContext.get().get<CalendarFollowStore>()
        val previous = follows.followed.value
        try {
            follows.unfollowAll()
            launchMainActivityForInput().use {
                await("No subscriptions leave no reminder jobs") { noReminderWork() }
                follows.follow(FollowedSeries(19_000_002, "Observer fixture"))
                follows.setReminder(19_000_002, CalendarReminderMode.AtBroadcast)
                await("Activity observer schedules the enabled reminder") { active(periodicName).isNotEmpty() }
                follows.unfollow(19_000_002)
                await("Activity observer cancels the removed reminder") { noReminderWork() }
            }
        } finally {
            follows.replaceFromSync(previous).getOrThrow()
            scheduleCalendarReminderWork(context, follows)
        }
    }

    private fun active(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS).filterNot { it.state.isFinished }

    private fun noReminderWork(): Boolean = active(periodicName).isEmpty() && active(immediateName).isEmpty()

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue(message, condition())
    }
}
