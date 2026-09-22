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
        assertEquals(
            CalendarReminderMode.BeforeAndAtBroadcast,
            restored.followed.value
                .single()
                .reminderMode,
        )
        assertEquals(
            24 * 60,
            restored.followed.value
                .single()
                .remindBeforeMinutes,
        )

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
    fun cloud_restore_cleans_reminder_state_for_removed_series() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.follow(FollowedSeries(tmdbId = 1399, title = "旧剧"))
        store.follow(FollowedSeries(tmdbId = 1400, title = "保留剧"))
        settings.putBoolean("calendar.reminder.available.baseline.1399", true)
        settings.putBoolean("calendar.reminder.sent.air.1399.2026-08-25", true)

        store.replaceFromSync(listOf(FollowedSeries(tmdbId = 1400, title = "保留剧"))).getOrThrow()

        assertNull(settings.getBooleanOrNull("calendar.reminder.available.baseline.1399"))
        assertNull(settings.getBooleanOrNull("calendar.reminder.sent.air.1399.2026-08-25"))
    }

    @Test
    fun changing_reminder_mode_resets_availability_baseline() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.follow(
            FollowedSeries(
                tmdbId = 1399,
                title = "测试剧",
                reminderMode = CalendarReminderMode.WhenAvailable,
            ),
        )
        settings.putBoolean("calendar.reminder.available.baseline.1399", true)

        store.setReminder(1399, CalendarReminderMode.AtBroadcast)

        assertNull(settings.getBooleanOrNull("calendar.reminder.available.baseline.1399"))
    }

    @Test
    fun bulk_actions_update_reminders_and_clean_all_follow_state() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.follow(FollowedSeries(tmdbId = 1, title = "剧一"))
        store.follow(FollowedSeries(tmdbId = 2, title = "剧二"))
        settings.putBoolean("calendar.reminder.sent.air.1.2026-08-25", true)

        store.setReminderForAll(CalendarReminderMode.BeforeAndAtBroadcast, beforeMinutes = 120)

        assertTrue(
            store.followed.value.all {
                it.reminderMode == CalendarReminderMode.BeforeAndAtBroadcast &&
                    it.remindBeforeMinutes == 120
            },
        )

        store.unfollowAll()

        assertTrue(store.followed.value.isEmpty())
        assertNull(settings.getBooleanOrNull("calendar.reminder.sent.air.1.2026-08-25"))
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

    @Test
    fun library_series_are_auto_followed_with_availability_reminders() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)

        val added =
            store.autoFollowLibrarySeries(
                listOf(FollowedSeries(tmdbId = 272938, title = "师兄太稳健")),
            )

        assertEquals(1, added)
        val followed = store.followed.value.single()
        assertEquals(CalendarTrackingOrigin.LibraryAuto, followed.trackingOrigin)
        assertEquals(CalendarReminderMode.WhenAvailable, followed.reminderMode)
        assertEquals(followed, CalendarFollowStore(settings).followed.value.single())
    }

    @Test
    fun cancelling_an_auto_follow_prevents_automatic_readdition() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        val candidate = FollowedSeries(tmdbId = 272938, title = "师兄太稳健")
        store.autoFollowLibrarySeries(listOf(candidate))

        store.unfollow(candidate.tmdbId)
        val addedAgain = CalendarFollowStore(settings).autoFollowLibrarySeries(listOf(candidate))

        assertEquals(0, addedAgain)
        assertFalse(CalendarFollowStore(settings).isFollowing(candidate.tmdbId))
    }

    @Test
    fun manual_follow_stays_manual_and_later_cancel_still_blocks_auto_follow() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        val candidate = FollowedSeries(tmdbId = 272938, title = "师兄太稳健")
        store.autoFollowLibrarySeries(listOf(candidate))
        store.unfollow(candidate.tmdbId)

        store.follow(candidate)

        assertEquals(CalendarTrackingOrigin.Manual, store.followed.value.single().trackingOrigin)
        store.unfollow(candidate.tmdbId)
        assertEquals(0, store.autoFollowLibrarySeries(listOf(candidate)))
    }

    @Test
    fun successful_server_scan_prunes_only_stale_automatic_follows() {
        val store = CalendarFollowStore(MapSettings())
        store.autoFollowLibrarySeries(
            listOf(
                FollowedSeries(tmdbId = 1, title = "继续追", serverId = "server-a"),
                FollowedSeries(tmdbId = 2, title = "已不活跃", serverId = "server-a"),
                FollowedSeries(tmdbId = 3, title = "失败服务器保留", serverId = "server-b"),
            ),
        )
        store.follow(FollowedSeries(tmdbId = 4, title = "手动追剧", serverId = "server-a"))

        val result =
            store.reconcileAutoFollowLibrarySeries(
                series = listOf(FollowedSeries(tmdbId = 1, title = "继续追（新标题）", serverId = "server-a")),
                authoritativeServerIds = setOf("server-a"),
            )

        assertEquals(1, result.removed)
        assertEquals(setOf(1, 3, 4), store.followed.value.map(FollowedSeries::tmdbId).toSet())
        assertEquals("继续追（新标题）", store.followed.value.first { it.tmdbId == 1 }.title)
        assertEquals(CalendarTrackingOrigin.Manual, store.followed.value.first { it.tmdbId == 4 }.trackingOrigin)
    }

    @Test
    fun failed_server_scan_never_prunes_automatic_follows() {
        val store = CalendarFollowStore(MapSettings())
        store.autoFollowLibrarySeries(
            listOf(FollowedSeries(tmdbId = 1, title = "保留剧", serverId = "server-a")),
        )

        val result = store.reconcileAutoFollowLibrarySeries(emptyList(), authoritativeServerIds = emptySet())

        assertEquals(0, result.removed)
        assertTrue(store.isFollowing(1))
    }

    @Test
    fun automatic_refresh_timestamp_prevents_duplicate_background_scans() {
        val store = CalendarFollowStore(MapSettings())

        assertTrue(store.automaticFollowRefreshDue(nowEpochMs = 1_000_000L, maxAgeMs = 60_000L))
        store.markAutomaticFollowRefresh(980_000L)
        assertFalse(store.automaticFollowRefreshDue(nowEpochMs = 1_000_000L, maxAgeMs = 60_000L))
        assertTrue(store.automaticFollowRefreshDue(nowEpochMs = 1_100_000L, maxAgeMs = 60_000L))
    }
}
