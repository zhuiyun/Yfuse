package com.yfuse.feature.watch

import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.WatchTogetherPreferences
import com.yfuse.core.sync.WatchTogetherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherSettingsStoreTest {
    private val scheduler = TestCoroutineScheduler()

    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_page_shows_the_room_and_this_devices_room_profile() =
        runTest(scheduler) {
            val room = FakeRoom()
            val preferences = WatchTogetherPreferences(MapSettings()).apply { setProfile("追云", 3) }
            val store = store(room, preferences)
            assertFalse(store.state.connected)
            assertEquals("追云", store.state.nickname)
            assertEquals(3, store.state.avatarId)

            room.state.value =
                WatchTogetherState(connected = true, roomCode = "ABC234", participantCount = 3, isHost = true)
            runCurrent()

            assertTrue(store.state.connected)
            assertEquals("ABC234", store.state.roomCode)
            assertEquals(3, store.state.participantCount)
            assertTrue(store.state.isHost)
            store.dispose()
        }

    @Test
    fun the_join_dialog_reports_the_rooms_error_before_what_keeps_it_from_following() =
        runTest(scheduler) {
            val room = FakeRoom()
            val store = store(room)

            room.state.value = WatchTogetherState(connected = true, syncWarning = "房间在播放你的媒体库里没有的内容，无法一起看")
            runCurrent()
            assertEquals("房间在播放你的媒体库里没有的内容，无法一起看", store.state.problem)

            room.state.value = room.state.value.copy(error = "房间不存在")
            runCurrent()
            assertEquals("房间不存在", store.state.problem)

            room.state.value = WatchTogetherState()
            runCurrent()
            assertNull(store.state.problem)
            store.dispose()
        }

    @Test
    fun a_code_joins_through_the_official_address_with_no_title() {
        val room = FakeRoom()
        val store = store(room)

        store.accept(WatchTogetherSettingsIntent.Join("ABC234"))

        assertEquals(listOf(Triple(WatchTogetherPreferences.DEFAULT_ENDPOINT, "ABC234", "")), room.joined)
        store.dispose()
    }

    @Test
    fun leaving_goes_to_the_room() {
        val room = FakeRoom()
        val store = store(room)

        store.accept(WatchTogetherSettingsIntent.Leave)

        assertEquals(1, room.leaves)
        store.dispose()
    }

    @Test
    fun the_room_hears_the_profile_the_preferences_kept() =
        runTest(scheduler) {
            val room = FakeRoom()
            val preferences = WatchTogetherPreferences(MapSettings())
            val store = store(room, preferences)

            store.accept(WatchTogetherSettingsIntent.SaveProfile(" 追云\n", 5))
            store.accept(WatchTogetherSettingsIntent.SaveProfile("   ", 99))
            runCurrent()

            assertEquals(listOf("追云" to 5, WatchTogetherPreferences.DEFAULT_NICKNAME to 7), room.profiles)
            assertEquals(WatchTogetherPreferences.DEFAULT_NICKNAME, store.state.nickname)
            assertEquals(7, store.state.avatarId)
            store.dispose()
        }

    @Test
    fun the_chat_display_switches_are_kept_on_this_device() =
        runTest(scheduler) {
            val preferences = WatchTogetherPreferences(MapSettings())
            val store = store(FakeRoom(), preferences)

            store.accept(WatchTogetherSettingsIntent.SetChatDanmaku(false))
            store.accept(WatchTogetherSettingsIntent.SetChatPreview(false))
            runCurrent()

            assertFalse(preferences.chatDanmakuEnabled.value)
            assertFalse(preferences.chatPreviewEnabled.value)
            assertFalse(store.state.chatDanmaku)
            assertFalse(store.state.chatPreview)
            store.dispose()
        }

    private fun store(
        room: FakeRoom,
        preferences: WatchTogetherPreferences = WatchTogetherPreferences(MapSettings()),
    ): Store<WatchTogetherSettingsIntent, WatchTogetherSettingsState, Nothing> =
        WatchTogetherSettingsStoreFactory(DefaultStoreFactory(), room, preferences).create()

    private class FakeRoom : WatchRoom {
        override val state = MutableStateFlow(WatchTogetherState())
        val joined = mutableListOf<Triple<String, String, String>>()
        val profiles = mutableListOf<Pair<String, Int>>()
        var leaves = 0

        override fun joinRoom(
            endpoint: String,
            roomCode: String,
            mediaKey: String,
        ) {
            joined += Triple(endpoint, roomCode, mediaKey)
        }

        override fun leave() {
            leaves++
        }

        override fun updateProfile(
            name: String,
            avatarId: Int,
        ) {
            profiles += name to avatarId
        }
    }
}
