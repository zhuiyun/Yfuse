package com.yfuse.core2.demux

import kotlin.test.Test
import kotlin.test.assertEquals

class ShownVideoSizeTest {
    @Test
    fun a_quarter_turned_stream_is_shown_on_its_side() {
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = 90))
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = 270))
        assertEquals(1080 to 1920, shownVideoSize(1920, 1080, rotationDegrees = -90))
    }

    @Test
    fun an_upright_or_upside_down_stream_keeps_its_size() {
        assertEquals(1920 to 1080, shownVideoSize(1920, 1080, rotationDegrees = 0))
        assertEquals(1920 to 1080, shownVideoSize(1920, 1080, rotationDegrees = 180))
        assertEquals(1080 to 1920, shownVideoSize(1080, 1920, rotationDegrees = 0))
    }
}
