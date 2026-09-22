package com.yfuse.tv.player

import android.media.session.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvMediaSessionAdapterTest {
    @Test
    fun queue_actions_only_advertise_available_directions() {
        val base = platformPlaybackActions(hasPrevious = false, hasNext = false)
        assertTrue(base has PlaybackState.ACTION_PLAY_PAUSE)
        assertTrue(base has PlaybackState.ACTION_STOP)
        assertTrue(base has PlaybackState.ACTION_SEEK_TO)
        assertFalse(base has PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertFalse(base has PlaybackState.ACTION_SKIP_TO_NEXT)

        val queue = platformPlaybackActions(hasPrevious = true, hasNext = true)
        assertTrue(queue has PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        assertTrue(queue has PlaybackState.ACTION_SKIP_TO_NEXT)
    }

    @Test
    fun error_and_buffering_take_precedence_over_transport_intent() {
        assertEquals(
            PlaybackState.STATE_ERROR,
            platformPlaybackState(
                error = "decoder",
                ended = false,
                buffering = true,
                playing = true,
            ),
        )
        assertEquals(
            PlaybackState.STATE_BUFFERING,
            platformPlaybackState(
                error = null,
                ended = false,
                buffering = true,
                playing = true,
            ),
        )
        assertEquals(
            PlaybackState.STATE_PAUSED,
            platformPlaybackState(
                error = null,
                ended = false,
                buffering = false,
                playing = false,
            ),
        )
    }
}

private infix fun Long.has(flag: Long): Boolean = this and flag != 0L
