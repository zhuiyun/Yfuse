package com.yfuse.feature.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchPlaylistPlaybackRequestTest {
    @AfterTest
    fun tearDown() {
        WatchPlaylistPlaybackRequest.clear()
    }

    @Test
    fun request_is_consumed_once() {
        WatchPlaylistPlaybackRequest.request("tmdb:42")

        assertEquals("tmdb:42", WatchPlaylistPlaybackRequest.consume())
        assertNull(WatchPlaylistPlaybackRequest.consume())
    }

    @Test
    fun newer_request_replaces_unconsumed_request() {
        WatchPlaylistPlaybackRequest.request("tmdb:1")
        WatchPlaylistPlaybackRequest.request("tmdb:2")

        assertEquals("tmdb:2", WatchPlaylistPlaybackRequest.consume())
    }

    @Test
    fun blank_request_is_ignored() {
        WatchPlaylistPlaybackRequest.request("   ")

        assertNull(WatchPlaylistPlaybackRequest.consume())
    }
}
