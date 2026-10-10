package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.personal.PersonalLibraryRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarReminderSubscriptionTest {
    @Test
    fun only_manual_follows_with_enabled_reminders_can_notify() {
        val store = CalendarFollowStore(MapSettings())
        store.follow(FollowedSeries(1, "手动追更但关闭提醒"))
        CalendarReminderMode.entries.filterNot { it == CalendarReminderMode.Off }.forEachIndexed { index, mode ->
            store.follow(FollowedSeries(index + 2, "订阅$index", reminderMode = mode))
        }
        store.autoFollowLibrarySeries(listOf(FollowedSeries(10, "媒体库自动发现")))

        assertEquals(setOf(2, 3, 4), store.notificationSubscriptions().map { it.tmdbId }.toSet())
    }

    @Test
    fun old_automatic_follows_with_enabled_reminders_are_also_silent() {
        val store = legacyAutoStore()

        assertEquals(
            CalendarReminderMode.WhenAvailable,
            store.followed.value
                .single()
                .reminderMode,
        )
        assertTrue(store.notificationSubscriptions().isEmpty())
    }

    @Test
    fun individually_enabling_reminders_converts_an_automatic_row_to_a_manual_subscription() {
        val settings = MapSettings()
        val store = CalendarFollowStore(settings)
        store.autoFollowLibrarySeries(listOf(FollowedSeries(10, "媒体库自动发现", serverId = "server")))
        store.setReminder(10, CalendarReminderMode.AtBroadcast)
        store.reconcileAutoFollowLibrarySeries(emptyList(), authoritativeServerIds = setOf("server"))

        assertEquals(
            CalendarTrackingOrigin.Manual,
            store.followed.value
                .single()
                .trackingOrigin,
        )
        assertEquals(listOf(10), CalendarFollowStore(settings).notificationSubscriptions().map { it.tmdbId })
    }

    @Test
    fun bulk_enabling_does_not_subscribe_automatically_discovered_series() {
        val store = CalendarFollowStore(MapSettings())
        store.follow(FollowedSeries(1, "自己的订阅"))
        store.autoFollowLibrarySeries(listOf(FollowedSeries(10, "媒体库自动发现")))

        store.setReminderForAll(CalendarReminderMode.BeforeAndAtBroadcast)

        assertEquals(listOf(1), store.notificationSubscriptions().map { it.tmdbId })
        assertEquals(
            CalendarReminderMode.Off,
            store.followed.value
                .single { it.tmdbId == 10 }
                .reminderMode,
        )
        assertEquals(
            CalendarTrackingOrigin.LibraryAuto,
            store.followed.value
                .single { it.tmdbId == 10 }
                .trackingOrigin,
        )
    }

    @Test
    fun bulk_disabling_also_clears_old_automatic_reminder_modes() {
        val store = legacyAutoStore()

        store.setReminderForAll(CalendarReminderMode.Off)

        assertEquals(
            CalendarReminderMode.Off,
            store.followed.value
                .single()
                .reminderMode,
        )
        assertTrue(store.notificationSubscriptions().isEmpty())
    }

    @Test
    fun unfollow_during_lookup_prevents_delivery_from_the_old_snapshot() {
        val store = CalendarFollowStore(MapSettings())
        store.follow(FollowedSeries(1, "自己的订阅", reminderMode = CalendarReminderMode.AtBroadcast))
        val oldSnapshot = store.notificationSubscriptions()
        val token = store.scopeToken
        store.unfollow(1)
        var delivered = 0

        assertTrue(store.runWithReminderSubscriptions(token) { delivered += it.size })
        assertEquals(1, oldSnapshot.size)
        assertEquals(0, delivered)
    }

    @Test
    fun disabling_during_lookup_prevents_pending_change_and_episode_reminders() {
        val store = CalendarFollowStore(MapSettings())
        store.follow(FollowedSeries(1, "自己的订阅", reminderMode = CalendarReminderMode.WhenAvailable))
        val token = store.scopeToken
        store.setReminder(1, CalendarReminderMode.Off)
        var delivered = 0

        assertTrue(store.runWithReminderSubscriptions(token) { delivered += it.size })
        assertEquals(0, delivered)
    }

    @Test
    fun old_profile_work_cannot_deliver_new_profile_subscriptions() =
        runTest {
            val settings = MapSettings()
            val personal = PersonalLibraryRepository(settings)
            val store = CalendarFollowStore(settings, personal)
            store.follow(FollowedSeries(1, "原资料订阅", reminderMode = CalendarReminderMode.AtBroadcast))
            val oldToken = store.scopeToken
            personal.saveProfile(name = "新资料", child = false, serverIds = emptySet()).getOrThrow()
            personal
                .switchProfile(
                    personal.state.value.profiles
                        .last()
                        .id,
                ).getOrThrow()
            store.follow(FollowedSeries(2, "新资料订阅", reminderMode = CalendarReminderMode.WhenAvailable))
            var delivered = 0

            assertFalse(store.runWithReminderSubscriptions(oldToken) { delivered += it.size })
            assertEquals(0, delivered)
            assertTrue(
                store.runWithReminderSubscriptions(store.scopeToken) { assertEquals(listOf(2), it.map { it.tmdbId }) },
            )
        }

    @Test
    fun account_switches_isolate_subscriptions_and_reject_work_from_an_earlier_login() {
        val settings = MapSettings()
        val personal = PersonalLibraryRepository(settings)
        personal.bindAccount("alice")
        val store = CalendarFollowStore(settings, personal)
        store.follow(FollowedSeries(1, "Alice 的订阅", reminderMode = CalendarReminderMode.AtBroadcast))
        val oldToken = store.scopeToken
        personal.bindAccount("bob")
        assertTrue(store.notificationSubscriptions().isEmpty())
        store.follow(FollowedSeries(2, "Bob 的订阅", reminderMode = CalendarReminderMode.WhenAvailable))

        assertFalse(store.runWithReminderSubscriptions(oldToken) { error("Old account must not deliver") })
        assertEquals(listOf(2), store.notificationSubscriptions().map { it.tmdbId })
        personal.bindAccount("alice")
        assertEquals(listOf(1), store.notificationSubscriptions().map { it.tmdbId })
        assertFalse(store.runWithReminderSubscriptions(oldToken) { error("Earlier login must not deliver") })
    }

    private fun legacyAutoStore(): CalendarFollowStore {
        val settings = MapSettings()
        settings.putString(
            "calendar.followed.series.v1",
            Json.encodeToString(
                ListSerializer(FollowedSeries.serializer()),
                listOf(
                    FollowedSeries(
                        10,
                        "旧版自动追踪",
                        reminderMode = CalendarReminderMode.WhenAvailable,
                        trackingOrigin = CalendarTrackingOrigin.LibraryAuto,
                    ),
                ),
            ),
        )
        return CalendarFollowStore(settings)
    }
}
