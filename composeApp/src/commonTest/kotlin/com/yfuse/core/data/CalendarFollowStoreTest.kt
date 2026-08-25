package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarFollowStoreTest {
    @Test
    fun follows_persist_and_reminders_are_bounded() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.follow(FollowedSeries(tmdbId = 272938, title = "师兄太稳健"))
        store.setReminder(272938, CalendarReminderMode.BeforeAndAtBroadcast, beforeMinutes = 99_999)

        val restored = CalendarFollowStore(settings)
        assertTrue(restored.isFollowing(272938))
        assertEquals(CalendarReminderMode.BeforeAndAtBroadcast, restored.followed.value.single().reminderMode)
        assertEquals(24 * 60, restored.followed.value.single().remindBeforeMinutes)

        restored.unfollow(272938)
        assertFalse(restored.isFollowing(272938))
    }
    @Test
    fun cloud_restore_validates_and_replaces_followed_series() {
        val store = CalendarFollowStore(MapSettings())
        val synced =
            FollowedSeries(
                tmdbId = 1399,
                title = "测试剧",
                reminderMode = CalendarReminderMode.BeforeAndAtBroadcast,
                remindBeforeMinutes = 120,
            )

        store.replaceFromSync(listOf(synced, synced)).getOrThrow()

        assertEquals(listOf(synced), store.followed.value)
    }

    @Test
    fun unfollow_removes_delivery_and_baseline_keys() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.follow(FollowedSeries(tmdbId = 1399, title = "测试剧"))
        settings.putBoolean("calendar.reminder.available.baseline.1399", true)
        settings.putBoolean("calendar.reminder.available.seen.1399.1.2", true)
        settings.putBoolean("calendar.reminder.sent.air.1399.2026-08-25", true)

        store.unfollow(1399)

        assertNull(settings.getBooleanOrNull("calendar.reminder.available.baseline.1399"))
        assertNull(settings.getBooleanOrNull("calendar.reminder.available.seen.1399.1.2"))
        assertNull(settings.getBooleanOrNull("calendar.reminder.sent.air.1399.2026-08-25"))
    }

}
