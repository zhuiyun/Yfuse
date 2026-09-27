package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DragSelectTest {
    private val keys = listOf("e1", "e2", "e3", "e4", "e5", "e6")

    @Test
    fun aSweepSelectsTheRunFromItsFirstRowToTheFingerInEitherDirection() {
        assertEquals(setOf("e2", "e3", "e4"), sweepSelection(keys, emptySet(), anchor = 1, current = 3, adding = true))
        assertEquals(setOf("e2", "e3", "e4"), sweepSelection(keys, emptySet(), anchor = 3, current = 1, adding = true))
        assertEquals(setOf("e3"), sweepSelection(keys, emptySet(), anchor = 2, current = 2, adding = true))
    }

    @Test
    fun sweepingBackGivesRowsTheirEarlierState() {
        val base = setOf("e1", "e5")
        val out = sweepSelection(keys, base, anchor = 1, current = 4, adding = true)
        assertEquals(setOf("e1", "e2", "e3", "e4", "e5"), out)
        // The finger returns to e3: e4 goes back to unselected, e5 stays selected as it was.
        assertEquals(setOf("e1", "e2", "e3", "e5"), sweepSelection(keys, base, anchor = 1, current = 2, adding = true))
    }

    @Test
    fun aSweepThatStartsOnASelectedRowDeselects() {
        val base = keys.toSet()
        assertEquals(setOf("e1", "e5", "e6"), sweepSelection(keys, base, anchor = 1, current = 3, adding = false))
    }

    @Test
    fun anAnchorOffTheListChangesNothingAndTheFingerIsHeldToTheList() {
        val base = setOf("e2")
        assertEquals(base, sweepSelection(keys, base, anchor = 9, current = 2, adding = true))
        assertEquals(setOf("e2", "e5", "e6"), sweepSelection(keys, base, anchor = 4, current = 40, adding = true))
    }

    /** A 600px list with the 44px edge bands, scrolling at most 1000px/s. */
    private fun speedAt(pointer: Float): Float =
        dragSelectScrollSpeed(pointer, top = 0f, bottom = 600f, edge = 44f, maxSpeed = 1_000f)

    @Test
    fun theListScrollsOnlyInsideItsEdgesAndFasterDeeperIn() {
        assertEquals(0f, speedAt(300f), 0.001f)
        assertEquals(-500f, speedAt(22f), 0.001f)
        assertEquals(1_000f, speedAt(600f), 0.001f)
        // Past the list's edge is full speed, not more.
        assertEquals(-1_000f, speedAt(-80f), 0.001f)
        assertEquals(1_000f, speedAt(900f), 0.001f)
        assertTrue(speedAt(590f) > speedAt(570f))
        assertTrue(speedAt(570f) > 0f)
        assertEquals(0f, speedAt(Float.NaN), 0.001f)
    }

    @Test
    fun aRowIsFoundUnderTheFingerOrNearestIt() {
        val rows =
            listOf(
                DragSelectRowSpan("e1", top = 0f, bottom = 60f),
                DragSelectRowSpan("e2", top = 68f, bottom = 128f),
                DragSelectRowSpan("e3", top = 136f, bottom = 196f),
            )
        assertEquals("e2", dragSelectRowAt(100f, rows, exact = true))
        // The 8px gap between rows: a sweep reaches the nearer row, a first press finds none.
        assertEquals("e2", dragSelectRowAt(66f, rows, exact = false))
        assertNull(dragSelectRowAt(64f, rows, exact = true))
        assertEquals("e3", dragSelectRowAt(400f, rows, exact = false))
        assertEquals("e1", dragSelectRowAt(-20f, rows, exact = false))
        assertNull(dragSelectRowAt(100f, emptyList<DragSelectRowSpan<String>>(), exact = false))
    }
}
