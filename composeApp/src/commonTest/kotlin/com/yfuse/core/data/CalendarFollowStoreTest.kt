package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
}
