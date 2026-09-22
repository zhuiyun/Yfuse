package com.yfuse.feature.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadTrackGeometryTest {
    @Test
    fun rtl_mirrors_start_alignment_and_preserves_nested_pixel_rounding() {
        val ltr = downloadTrackGeometry(101f, 0.5f, 0.5f, rtl = false)
        val rtl = downloadTrackGeometry(101f, 0.5f, 0.5f, rtl = true)
        assertEquals(51f, ltr.trackWidth)
        assertEquals(26f, ltr.fillWidth)
        assertEquals(ltr.trackWidth, rtl.trackWidth)
        assertEquals(ltr.fillWidth, rtl.fillWidth)
        assertEquals(101f, rtl.trackLeft + rtl.trackWidth)
        assertEquals(101f, rtl.fillLeft + rtl.fillWidth)
        assertEquals(0f, ltr.trackLeft)
        assertEquals(0f, ltr.fillLeft)
    }

    @Test
    fun spring_overshoot_and_completion_never_draw_outside_the_track() {
        for (rtl in listOf(false, true)) {
            for (progress in listOf(-0.2f, 0f, 0.5f, 1f, 1.2f)) {
                for (collapse in listOf(-0.2f, 0f, 0.5f, 1f, 1.2f)) {
                    val geometry = downloadTrackGeometry(101f, progress, collapse, rtl)
                    assertTrue(geometry.trackLeft >= 0f)
                    assertTrue(geometry.trackLeft + geometry.trackWidth <= 101f)
                    assertTrue(geometry.fillLeft >= geometry.trackLeft)
                    assertTrue(geometry.fillLeft + geometry.fillWidth <= geometry.trackLeft + geometry.trackWidth)
                }
            }
            assertEquals(0f, downloadTrackGeometry(101f, 1f, 1f, rtl).trackWidth)
            assertEquals(0f, downloadTrackGeometry(0f, 1f, 0f, rtl).fillWidth)
        }
    }
}
