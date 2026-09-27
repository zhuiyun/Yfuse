package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UndoWindowTest {
    @Test
    fun aNewChangeSendsThePreviousOneOnItsWay() {
        val window = UndoWindow<String>()
        assertNull(window.hold("a"))
        assertEquals("a", window.hold("b"))
        assertEquals("b", window.current)
    }

    @Test
    fun undoOnlyTakesBackTheChangeItNames() {
        val window = UndoWindow<String>()
        window.hold("a")
        window.hold("b")
        // The toast for "a" is stale: "a" has already been committed.
        assertNull(window.undo { it == "a" })
        assertEquals("b", window.undo { it == "b" })
        assertNull(window.current)
    }

    @Test
    fun releasingAfterAnUndoCommitsNothing() {
        val window = UndoWindow<String>()
        window.hold("a")
        window.undo { it == "a" }
        assertNull(window.release())
    }

    @Test
    fun releaseHandsOverWhatWasWaitingOnce() {
        val window = UndoWindow<String>()
        window.hold("a")
        assertEquals("a", window.release())
        assertNull(window.release())
    }
}
