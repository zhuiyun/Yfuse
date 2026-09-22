package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTrackRequestTest {
    @Test
    fun incomplete_tracks_do_not_consume_the_language_and_each_applied_choice_is_acknowledged() {
        val request = PlaybackTrackRequest()
        request.set("movie", "zho", PlaybackTrackRequest.SUBTITLES_OFF)
        val original = requireNotNull(request.peek("movie"))
        request.acknowledge("movie", original, audioApplied = false, subtitleApplied = true)
        assertEquals(PlaybackTrackRequest.Tracks("zho", null), request.peek("movie"))
        val remaining = requireNotNull(request.peek("movie"))
        request.acknowledge("movie", remaining, audioApplied = true, subtitleApplied = true)
        assertNull(request.peek("movie"))
    }

    @Test
    fun acknowledging_an_old_or_different_item_cannot_discard_a_new_choice() {
        val request = PlaybackTrackRequest()
        request.set("movie", "en")
        val old = requireNotNull(request.peek("movie"))
        request.set("movie", "zh")
        request.acknowledge("movie", old, true, true)
        request.acknowledge("other", requireNotNull(request.peek("movie")), true, true)
        assertEquals("zh", request.peek("movie")?.audioLanguage)
    }

    @Test
    fun the_request_reaches_the_entry_it_was_made_for() {
        val request = PlaybackTrackRequest()
        request.set("item-1", audioLanguage = "chi", subtitleLanguage = "eng")

        val taken = request.consume("item-1")

        assertEquals("chi", taken?.audioLanguage)
        assertEquals("eng", taken?.subtitleLanguage)
    }

    @Test
    fun it_is_consumed_once_so_the_next_episode_starts_clean() {
        val request = PlaybackTrackRequest()
        request.set("item-1", audioLanguage = "chi")

        request.consume("item-1")

        assertNull(request.consume("item-1"))
    }

    @Test
    fun a_player_opening_for_something_else_finds_nothing() {
        val request = PlaybackTrackRequest()
        request.set("item-1", audioLanguage = "chi")

        assertNull(request.consume("item-2"))
        // And the request is still there for the entry it was meant for.
        assertEquals("chi", request.consume("item-1")?.audioLanguage)
    }

    @Test
    fun stating_no_preference_stores_nothing() {
        val request = PlaybackTrackRequest()
        request.set("item-1", audioLanguage = "chi")
        request.set("item-1", audioLanguage = null, subtitleLanguage = null)

        assertNull(request.consume("item-1"))
    }
}
