package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PaneNavigationTest {
    @Test
    fun selectingFromRetainedGridReplacesTheEntireOldDetailBranch() {
        val stack = listOf("home", "grid", "detail A", "related")
        assertEquals(listOf("home", "grid", "detail B"), stackAfterSelection(stack, "grid", "detail B"))
        assertEquals(listOf("home", "detail C"), stackAfterSelection(stack, "home", "detail C"))
    }

    @Test
    fun navigationInsideTheDetailStillPushesAndNeverDuplicatesARoute() {
        val stack = listOf("home", "detail", "related")
        assertEquals(stack + "cast", stackAfterSelection(stack, "related", "cast"))
        assertEquals(listOf("home", "related", "detail"), stackAfterSelection(stack, "related", "detail"))
        assertEquals(stack, stackAfterSelection(stack, "related", "related"))
    }

    @Test
    fun delayedSelectionFromADisposedSourceCannotReopenIt() {
        val stack = listOf("home", "new grid")
        assertEquals(stack, stackAfterSelection(stack, "old grid", "detail"))
    }
}
