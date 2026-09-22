package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TrickplayRegionTest {
    @Test
    fun only_requested_cell_is_decoded_including_last_partial_pixel() {
        assertEquals(TrickplayRegion(320, 180, 480, 270), trickplayRegion(1600, 900, 10, 10, 2, 2))
        assertEquals(TrickplayRegion(1440, 810, 1601, 901), trickplayRegion(1601, 901, 10, 10, 9, 9))
        assertEquals(TrickplayRegion(0, 0, 320, 180), trickplayRegion(320, 180, 1, 1, 0, 0))
    }

    @Test
    fun malformed_grid_and_out_of_range_cells_are_rejected() {
        assertFailsWith<IllegalArgumentException> { trickplayRegion(100, 100, 0, 1, 0, 0) }
        assertFailsWith<IllegalArgumentException> { trickplayRegion(100, 100, 10, 10, 10, 0) }
        assertFailsWith<IllegalArgumentException> { trickplayRegion(1, 1, 10, 10, 0, 0) }
    }
}
