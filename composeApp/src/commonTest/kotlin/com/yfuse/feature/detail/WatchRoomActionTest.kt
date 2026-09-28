package com.yfuse.feature.detail

import com.yfuse.core.sync.WatchTogetherState
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchRoomActionTest {
    @Test
    fun a_room_for_this_title_is_shared_again_rather_than_rebuilt() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", isHost = true, mediaKey = "tmdb:603")

        assertEquals(WatchRoomAction.Share, watchRoomAction(room, setOf("tmdb:603")))
    }

    @Test
    fun a_room_for_another_title_is_only_left_after_asking() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", mediaKey = "tmdb:603")

        assertEquals(WatchRoomAction.ConfirmReplace, watchRoomAction(room, setOf("tmdb:604")))
    }

    @Test
    fun a_room_is_created_only_when_there_is_none_and_an_untitled_one_is_kept() {
        assertEquals(WatchRoomAction.Create, watchRoomAction(WatchTogetherState(), setOf("tmdb:603")))
        assertEquals(WatchRoomAction.Create, watchRoomAction(WatchTogetherState(connecting = true), setOf("tmdb:603")))
        assertEquals(
            WatchRoomAction.Share,
            watchRoomAction(WatchTogetherState(connected = true, roomCode = "ABC123"), setOf("tmdb:603")),
        )
    }

    @Test
    fun a_show_whose_room_names_the_episode_playing_is_still_this_title() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", isHost = true, mediaKey = "tmdb:1399/s1e1")

        // The show's page, and an episode page that knows its show.
        assertEquals(WatchRoomAction.Share, watchRoomAction(room, setOf("tmdb:1399", "emby:series-1")))
        assertEquals(WatchRoomAction.Share, watchRoomAction(room, setOf("tvdb:9001", "emby:ep-2", "tmdb:1399")))
        assertEquals(WatchRoomAction.ConfirmReplace, watchRoomAction(room, setOf("tmdb:1400")))
    }

    @Test
    fun a_room_named_by_another_provider_matches_any_id_this_library_holds() {
        val room = WatchTogetherState(connected = true, roomCode = "ABC123", mediaKey = "imdb:tt0133093")

        assertEquals(WatchRoomAction.Share, watchRoomAction(room, setOf("tmdb:603", "imdb:tt0133093", "emby:42")))
    }
}
