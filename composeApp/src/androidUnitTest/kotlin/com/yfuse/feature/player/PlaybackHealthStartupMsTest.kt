package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `player.health`'s `playback_health_assessed` used to always report
 * [PlaybackHealthSession]'s own `startupTimeMs`, which is anchored to the current runtime
 * session - rebuilt on every `runtimeSessionGeneration` bump, chiefly a rebuffer recovery - so
 * after one it measured time-since-the-rebuffer rather than time-since-the-tap (one sample
 * logged `startupMs=2888` while the tap actually waited 6727 ms for its first frame).
 * [playbackHealthStartupMs] prefers the tap-anchored value whenever the caller still has one.
 */
class PlaybackHealthStartupMsTest {
    @Test
    fun prefers_the_tap_anchored_value_when_available() {
        assertEquals(6_727L, playbackHealthStartupMs(tapAnchoredStartupMs = 6_727L, sessionStartupTimeMs = 2_888L))
    }

    @Test
    fun falls_back_to_the_session_value_once_the_tap_anchor_is_gone() {
        assertEquals(2_888L, playbackHealthStartupMs(tapAnchoredStartupMs = null, sessionStartupTimeMs = 2_888L))
    }

    @Test
    fun stays_null_before_either_source_has_a_startup_time() {
        assertNull(playbackHealthStartupMs(tapAnchoredStartupMs = null, sessionStartupTimeMs = null))
    }
}
