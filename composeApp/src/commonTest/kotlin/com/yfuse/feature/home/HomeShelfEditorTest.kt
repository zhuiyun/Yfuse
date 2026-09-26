package com.yfuse.feature.home

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeShelfEditorTest {
    @Test
    fun aRowDropsIntoTheSlotItsCentreIsOver() {
        assertEquals(1, shelfDropIndex(from = 1, offset = 20f, rowHeight = 56f, count = 5))
        assertEquals(2, shelfDropIndex(from = 1, offset = 30f, rowHeight = 56f, count = 5))
        assertEquals(0, shelfDropIndex(from = 1, offset = -40f, rowHeight = 56f, count = 5))
        assertEquals(4, shelfDropIndex(from = 1, offset = 900f, rowHeight = 56f, count = 5))
        assertEquals(0, shelfDropIndex(from = 0, offset = -900f, rowHeight = 56f, count = 5))
    }

    @Test
    fun theRowsItPassesStepAsideTowardWhereItCameFrom() {
        // Row 1 held over slot 3: rows 2 and 3 move up one; the rest stay.
        assertEquals(listOf(0, 0, -1, -1, 0), (0..4).map { shelfRowShift(it, from = 1, target = 3) })
        // Row 3 held over slot 1: rows 1 and 2 move down one.
        assertEquals(listOf(0, 1, 1, 0, 0), (0..4).map { shelfRowShift(it, from = 3, target = 1) })
        // Nothing held.
        assertEquals(listOf(0, 0, 0), (0..2).map { shelfRowShift(it, from = -1, target = -1) })
    }
}
