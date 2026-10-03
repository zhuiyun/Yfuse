package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UndoWindowTest {
    private val committed = mutableListOf<String>()

    @Test
    fun aNewChangeSendsThePreviousOneOnItsWay() {
        val window = UndoWindow<String> { committed += it }
        window.hold("a")
        assertEquals(emptyList(), committed)

        window.hold("b")

        assertEquals(listOf("a"), committed)
        assertEquals("b", window.current)
    }

    @Test
    fun undoOnlyTakesBackTheChangeItNames() {
        val window = UndoWindow<String> { committed += it }
        window.hold("a")
        window.hold("b")
        // The toast for "a" is stale: "a" has already been committed.
        assertNull(window.undo { it == "a" })
        assertEquals("b", window.undo { it == "b" })
        assertNull(window.current)
        assertEquals(listOf("a"), committed)
    }

    @Test
    fun settlingAfterAnUndoCommitsNothing() {
        val window = UndoWindow<String> { committed += it }
        window.hold("a")
        window.undo { it == "a" }

        window.settle()

        assertEquals(emptyList(), committed)
    }

    @Test
    fun settlingCommitsWhatWasWaitingOnce() {
        val window = UndoWindow<String> { committed += it }
        window.hold("a")

        window.settle()
        window.settle()

        assertEquals(listOf("a"), committed)
        assertNull(window.current)
    }

    @Test
    fun releaseHandsOverWhatWasWaitingOnceWithoutCommittingIt() {
        val window = UndoWindow<String> { committed += it }
        window.hold("a")
        assertEquals("a", window.release())
        assertNull(window.release())

        window.settle()

        assertEquals(emptyList(), committed)
    }

    @Test
    fun theDisplacedCommitAlreadySeesTheNewerChangeWaiting() {
        // A store reloads its shelves from inside a commit; the newer change must stay off them.
        lateinit var window: UndoWindow<String>
        val seen = mutableListOf<String?>()
        window = UndoWindow { seen += window.current }
        window.hold("a")

        window.hold("b")

        assertEquals(listOf<String?>("b"), seen)
    }

    @Test
    fun theSettledCommitSeesNothingWaiting() {
        lateinit var window: UndoWindow<String>
        val seen = mutableListOf<String?>()
        window = UndoWindow { seen += window.current }
        window.hold("a")

        window.settle()

        assertEquals(listOf<String?>(null), seen)
    }
}
