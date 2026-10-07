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
        assertEquals("volume", gestureHudMotionKey(GestureHudReading("音量 20%", GestureHudKind.Volume)))
        assertEquals("volume", gestureHudMotionKey(GestureHudReading("音量 85%", GestureHudKind.Volume)))
        assertEquals("brightness", gestureHudMotionKey(GestureHudReading("亮度 42%", GestureHudKind.Brightness)))
        assertEquals("seek", gestureHudMotionKey(GestureHudReading("01:20 / 42:10", GestureHudKind.Seek)))
        assertEquals("hidden", gestureHudMotionKey(null))
    }

    @Test
    fun the_motion_follows_the_kind_never_the_wording() {
        // A track or chapter title may well start with 音量 or contain " / ".
        assertEquals("音轨 · 音量增强 / 杜比", gestureHudMotionKey(GestureHudReading("音轨 · 音量增强 / 杜比")))
        assertEquals("seek", gestureHudMotionKey(GestureHudReading("跳转 01:00", GestureHudKind.Seek)))
    }
}
