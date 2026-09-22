package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerMicroMotionTest {
    @Test
    fun short_buffering_keeps_the_transport_icon_stable() {
        assertEquals(
            TransportVisualState.Pause,
            transportVisualState(
                playing = true,
                buffering = true,
                bufferingIndicatorVisible = false,
            ),
        )
    }

    @Test
    fun sustained_buffering_replaces_transport_with_progress() {
        assertEquals(
            TransportVisualState.Buffering,
            transportVisualState(
                playing = false,
                buffering = true,
                bufferingIndicatorVisible = true,
            ),
        )
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
