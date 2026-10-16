package com.yfuse.core.designsystem

import androidx.activity.BackEventCompat
import androidx.navigationevent.NavigationEvent
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationBackCallbackTest {
    @Test
    fun firstStartIsSynchronousAndKeepsTheOriginalEventWithoutProgress() {
        val fixture = Fixture()
        fixture.callback.handleOnBackStarted(BackEventCompat(7f, 31f, 0f, BackEventCompat.EDGE_RIGHT))
        assertEquals(listOf("start"), fixture.events)
        val event = fixture.starts.single()
        assertEquals(7f, event.touchX)
        assertEquals(31f, event.touchY)
        assertEquals(NavigationEvent.EDGE_RIGHT, event.swipeEdge)
        fixture.callback.handleOnBackCancelled()
        assertEquals(listOf("start", "cancel"), fixture.events)
    }

    @Test
    fun eitherEdgeCanCommitWithoutAProgressEvent() {
        for (edge in listOf(BackEventCompat.EDGE_LEFT, BackEventCompat.EDGE_RIGHT)) {
            val fixture = Fixture()
            fixture.callback.handleOnBackStarted(BackEventCompat(1f, 30f, 0f, edge))
            fixture.callback.handleOnBackPressed()
            assertEquals(listOf("start", "complete"), fixture.events)
        }
    }

    @Test
    fun hidingAndReopeningRejectsTheOldGestureExactlyOnce() {
        val fixture = Fixture()
        fixture.start()
        fixture.visible = false
        fixture.callback.updateEnabled(false)
        fixture.visible = true
        fixture.callback.updateEnabled(true)
        fixture.callback.handleOnBackProgressed(BackEventCompat(100f, 30f, 0.4f, BackEventCompat.EDGE_LEFT))
        fixture.callback.handleOnBackPressed()
        assertEquals(listOf("start", "cancel"), fixture.events)
        fixture.start()
        fixture.callback.handleOnBackPressed()
        assertEquals(listOf("start", "cancel", "start", "complete"), fixture.events)
    }

    @Test
    fun aGestureStartedWhileHiddenCannotBecomeAnOrdinaryBack() {
        val fixture = Fixture()
        fixture.visible = false
        fixture.start()
        fixture.visible = true
        fixture.callback.handleOnBackPressed()
        assertEquals(emptyList(), fixture.events)
    }

    @Test
    fun lifecycleInvalidationCancelsButDoesNotCommitAfterRestart() {
        val fixture = Fixture()
        fixture.start()
        fixture.callback.invalidateGesture()
        fixture.callback.handleOnBackPressed()
        assertEquals(listOf("start", "cancel"), fixture.events)
        fixture.callback.handleOnBackPressed()
        assertEquals(listOf("start", "cancel", "complete"), fixture.events)
    }

    @Test
    fun liveVisibilityIsCheckedBeforeProgressEvenBeforeRecomposition() {
        val fixture = Fixture()
        fixture.start()
        fixture.visible = false
        fixture.callback.handleOnBackProgressed(BackEventCompat(100f, 30f, 0.4f, BackEventCompat.EDGE_LEFT))
        fixture.callback.handleOnBackCancelled()
        fixture.callback.handleOnBackPressed()
        assertEquals(listOf("start", "cancel"), fixture.events)
    }

    private class Fixture {
        var visible = true
        val events = mutableListOf<String>()
        val starts = mutableListOf<NavigationEvent>()
        val callback =
            NavigationBackCallback(
                acceptsBack = { visible },
                onStarted = {
                    starts += it
                    events += "start"
                },
                onProgressed = { events += "progress" },
                onCompleted = { events += "complete" },
                onCancelled = { events += "cancel" },
            ).apply { updateEnabled(true) }

        fun start() {
            callback.handleOnBackStarted(BackEventCompat(1f, 30f, 0f, BackEventCompat.EDGE_LEFT))
        }
    }
}
