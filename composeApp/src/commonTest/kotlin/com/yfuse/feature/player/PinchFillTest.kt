package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PinchFillTest {
    @Test
    fun spreading_fills_and_pinching_in_fits() {
        assertEquals(true, pinchFillTarget(filled = false, zoom = 1.2f))
        assertEquals(false, pinchFillTarget(filled = true, zoom = 0.8f))
    }

    @Test
    fun small_drifts_and_the_wrong_direction_ask_for_nothing() {
        assertNull(pinchFillTarget(filled = false, zoom = 1.1f))
        assertNull(pinchFillTarget(filled = true, zoom = 0.9f))
        assertNull(pinchFillTarget(filled = true, zoom = 1.6f))
        assertNull(pinchFillTarget(filled = false, zoom = 0.5f))
        assertNull(pinchFillTarget(filled = false, zoom = Float.NaN))
        assertNull(pinchFillTarget(filled = false, zoom = Float.POSITIVE_INFINITY))
    }

    @Test
    fun one_gesture_follows_the_fingers_back_and_forth() {
        val pinch = PinchFillTracker(startFilled = false, startSpan = 200f)
        assertNull(pinch.follow(220f))
        assertEquals(true, pinch.follow(240f))
        // Further spreading is already filled.
        assertNull(pinch.follow(320f))
        // Re-anchored at 240: back in past 240 / 1.15 fits again, within the same gesture.
        assertNull(pinch.follow(215f))
        assertEquals(false, pinch.follow(205f))
    }

    @Test
    fun a_zero_start_span_anchors_on_the_first_real_one() {
        val pinch = PinchFillTracker(startFilled = true, startSpan = 0f)
        assertNull(pinch.follow(300f))
        assertEquals(false, pinch.follow(250f))
    }

    @Test
    fun the_hud_names_the_mode() {
        assertEquals("画面：裁剪填满", pinchFillMessage(filled = true))
        assertEquals("画面：适应", pinchFillMessage(filled = false))
    }
}
