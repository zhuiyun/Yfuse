package com.yfuse.core2.android

import com.yfuse.core2.demux.YVideoGeometry
import kotlin.test.Test
import kotlin.test.assertEquals

/** The software route draws frames itself, so it squares and turns them itself. */
class AndroidVideoShapeTest {
    @Test
    fun an_anamorphic_frame_fills_a_surface_of_its_shown_shape() {
        val dvd = YVideoGeometry(pixelAspectRatioNumerator = 32, pixelAspectRatioDenominator = 27)

        assertEquals(1920f to 1080f, softwareFrameDrawSize(720, 480, dvd, 1920, 1080))
        // Square pixels in the same box are pillarboxed, as before.
        assertEquals(1620f to 1080f, softwareFrameDrawSize(720, 480, YVideoGeometry(), 1920, 1080))
    }

    @Test
    fun a_turned_frame_is_drawn_to_fit_once_turned() {
        val portrait = YVideoGeometry(rotationDegrees = 90)

        // Drawn 1920×1080 before the turn, it stands 1080×1920 in an upright surface.
        assertEquals(1920f to 1080f, softwareFrameDrawSize(1920, 1080, portrait, 1080, 1920))
        // In a landscape surface the upright picture is pillarboxed to 607.5 wide.
        assertEquals(1080f to 607.5f, softwareFrameDrawSize(1920, 1080, portrait, 1920, 1080))
    }

    @Test
    fun only_quarter_turns_are_drawn() {
        assertEquals(90, YVideoGeometry(rotationDegrees = -270).drawnRotationDegrees)
        assertEquals(180, YVideoGeometry(rotationDegrees = 540).drawnRotationDegrees)
        assertEquals(0, YVideoGeometry(rotationDegrees = 45).drawnRotationDegrees)
    }
}
