package com.yfuse.feature.detail

import com.yfuse.core.sync.WatchTogetherState
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchRoomActionTest {
    @Test
    fun a_room_for_this_title_is_shared_again_rather_than_rebuilt() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", isHost = true, mediaKey = "tmdb:603")

        assertEquals(WatchRoomAction.Share, watchRoomAction(room, "tmdb:603"))
    }

    @Test
    fun a_room_for_another_title_is_only_left_after_asking() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", mediaKey = "tmdb:603")

        assertEquals(WatchRoomAction.ConfirmReplace, watchRoomAction(room, "tmdb:604"))
    }

    @Test
    fun a_room_is_created_only_when_there_is_none_and_an_untitled_one_is_kept() {
        assertEquals(WatchRoomAction.Create, watchRoomAction(WatchTogetherState(), "tmdb:603"))
        assertEquals(WatchRoomAction.Create, watchRoomAction(WatchTogetherState(connecting = true), "tmdb:603"))
        assertEquals(
            WatchRoomAction.Share,
            watchRoomAction(WatchTogetherState(connected = true, roomCode = "ABC123"), "tmdb:603"),
        )
    }
}
