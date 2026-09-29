package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionTest {
    @Test
    fun aTapFlipsOneRowAndLeavesTheRest() {
        val selection = setOf("a", "c")
        assertEquals(setOf("a", "b", "c"), selection.toggling("b"))
        assertEquals(setOf("c"), selection.toggling("a"))
    }

    @Test
    fun selectAllTurnsIntoClearAllOnlyOnceEveryRowIsIn() {
        val rows = listOf("a", "b", "c")
        assertFalse(setOf("a", "b").coversAll(rows))
        assertTrue(setOf("a", "b", "c", "z").coversAll(rows))
        // A page with no rows has nothing to clear.
        assertFalse(setOf("a").coversAll(emptyList()))
    }

    @Test
    fun selectAllTicksTheShownRowsAndClearAllTakesOnlyThemBack() {
        val shown = listOf("a", "b")
        // "z" is selected under another filter and keeps its tick either way.
        val ticked = setOf("a", "z").selectingAll(shown)
        assertEquals(setOf("a", "b", "z"), ticked)
        assertEquals(setOf("z"), ticked.selectingAll(shown))
    }
}
