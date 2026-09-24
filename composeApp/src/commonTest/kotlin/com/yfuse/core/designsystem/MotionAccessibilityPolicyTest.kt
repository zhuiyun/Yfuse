package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionAccessibilityPolicyTest {
    @Test
    fun overlays_leave_faster_and_reduce_motion_is_instant() {
        assertEquals(DialogAnimation.Lift.enterMillis, overlayDurationMillis(leaving = false, reduceMotion = false))
        assertEquals(Motion.Dialog.EXIT_QUICK, overlayDurationMillis(leaving = true, reduceMotion = false))
        assertEquals(0, overlayDurationMillis(leaving = false, reduceMotion = true))
        assertEquals(0, overlayDurationMillis(leaving = true, reduceMotion = true))
    }

    @Test
    fun dense_posters_use_a_short_fade_and_fallbacks_keep_identity() {
        assertEquals(180, Motion.POSTER_FADE)
        assertEquals("海", imageFallbackMonogram(" 海报 "))
        assertEquals("Y", imageFallbackMonogram("yfuse"))
        assertEquals("—", imageFallbackMonogram(null))
    }

    @Test
    fun reduce_motion_removes_geometry_but_keeps_the_focus_ring() {
        assertEquals(
            1f,
            pressScaleTarget(
                reduceMotion = true,
                pressed = true,
                highlighted = true,
                pressedScale = 0.92f,
            ),
        )
        assertEquals(1f, focusRingTargetAlpha(enabled = true, focused = true))
        assertEquals(0f, focusRingTargetAlpha(enabled = false, focused = true))
    }

    @Test
    fun reduce_motion_answers_a_press_with_the_state_layer_instead_of_the_scale() {
        assertEquals(PRESSED_LAYER_ALPHA, layer(pressed = true, reduceMotion = true))
        // With motion on, the scale is the answer and ordinary controls are not washed twice.
        assertEquals(0f, layer(pressed = true))
        assertEquals(PRESSED_LAYER_ALPHA, layer(pressed = true, tintOnPress = true))
        val disabled = layer(pressed = true, hovered = true, reduceMotion = true, tintOnPress = true, enabled = false)
        assertEquals(0f, disabled)
    }

    @Test
    fun hover_is_a_quiet_wash_without_ring_or_lift() {
        assertEquals(HOVER_LAYER_ALPHA, layer(hovered = true))
        assertEquals(0f, focusRingTargetAlpha(enabled = true, focused = false))
        assertEquals(
            1f,
            pressScaleTarget(reduceMotion = false, pressed = false, highlighted = false, pressedScale = 0.97f),
        )
    }

    private fun layer(
        pressed: Boolean = false,
        hovered: Boolean = false,
        reduceMotion: Boolean = false,
        tintOnPress: Boolean = false,
        enabled: Boolean = true,
    ): Float = pressLayerTargetAlpha(enabled, pressed, hovered, reduceMotion, tintOnPress)
}
