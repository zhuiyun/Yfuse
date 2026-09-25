package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerMicroMotionTest {
    @Test
    fun buffering_keeps_the_transport_icon_it_had_settled_on() {
        // A seek mid-film: the engine stops reporting `playing` until the picture is back.
        assertTrue(transportShowsPause(playing = false, buffering = true, settledPlaying = true))
        // A seek while paused stays 播放.
        assertFalse(transportShowsPause(playing = false, buffering = true, settledPlaying = false))
    }

    @Test
    fun buffering_before_anything_has_settled_offers_pause() {
        assertTrue(transportShowsPause(playing = false, buffering = true, settledPlaying = null))
    }

    @Test
    fun outside_buffering_the_key_follows_the_engine() {
        assertTrue(transportShowsPause(playing = true, buffering = false, settledPlaying = false))
        assertFalse(transportShowsPause(playing = false, buffering = false, settledPlaying = true))
    }

    @Test
    fun numeric_gesture_updates_share_one_motion_surface() {
        assertEquals("volume", gestureHudMotionKey("音量 20%"))
        assertEquals("volume", gestureHudMotionKey("音量 85%"))
        assertEquals("brightness", gestureHudMotionKey("亮度 42%"))
        assertEquals("seek", gestureHudMotionKey("01:20 / 42:10"))
        assertEquals("hidden", gestureHudMotionKey(null))
    }
}
