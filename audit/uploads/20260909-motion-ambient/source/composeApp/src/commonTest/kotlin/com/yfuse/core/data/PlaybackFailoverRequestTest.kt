package com.yfuse.core.data

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFailoverRequestTest {
    @Test
    fun request_deduplicates_and_bounds_untrusted_plan() {
        val request = PlaybackFailoverRequest()
        request.set(
            PlaybackFailoverPlan(
                itemId = "movie",
                mediaKey = "tmdb:603",
                fallbackServerIds = listOf("a", "a", "b", "c", "d", "e"),
            ),
        )

        assertEquals(listOf("a", "b", "c"), request.consume("movie")?.fallbackServerIds)
    }

    @Test
    fun clear_revokes_pending_fallback() {
        val request = PlaybackFailoverRequest()
        request.set(PlaybackFailoverPlan("movie", "tmdb:603", listOf("a")))

        request.clear()

        assertEquals(null, request.consume("movie"))
    }
}
