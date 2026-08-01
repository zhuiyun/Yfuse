package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackTrackRequestTest {

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
