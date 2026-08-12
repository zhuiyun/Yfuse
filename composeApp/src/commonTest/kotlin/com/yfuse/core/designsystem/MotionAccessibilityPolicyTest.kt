package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

class MotionAccessibilityPolicyTest {

    @Test
    fun overlays_leave_faster_and_reduce_motion_is_instant() {
        assertEquals(Motion.MODAL, overlayDurationMillis(leaving = false, reduceMotion = false))
        assertEquals(OverlayExitDurationMs, overlayDurationMillis(leaving = true, reduceMotion = false))
        assertEquals(0, overlayDurationMillis(leaving = false, reduceMotion = true))
        assertEquals(0, overlayDurationMillis(leaving = true, reduceMotion = true))
    }

    @Test
    fun dense_posters_use_a_short_fade_and_fallbacks_keep_identity() {
        assertEquals(180, PosterFadeDurationMs)
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
        assertEquals(1f, focusRingTargetAlpha(enabled = true, focused = true, hovered = false))
        assertEquals(0f, focusRingTargetAlpha(enabled = false, focused = true, hovered = true))
    }
}
