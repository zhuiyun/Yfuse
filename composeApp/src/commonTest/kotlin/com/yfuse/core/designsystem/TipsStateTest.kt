package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TipsStateTest {
    private class MemoryStore : TipsStore {
        val retired = mutableSetOf<String>()
        var day: String? = null

        override fun isRetired(id: String) = id in retired

        override fun retire(id: String) {
            retired += id
        }

        override fun lastShownDay() = day

        override fun setLastShownDay(day: String) {
            this.day = day
        }
    }

    // Where a tip is placed; a page holds one of these per ContextualTip.
    private val home = Any()
    private val downloads = Any()

    @Test
    fun aTipShowsOnceAndRetiresOnceSeen() {
        val store = MemoryStore()
        var today = "2026-09-26"
        val tips = TipsState(store) { today }
        assertTrue(tips.claim(Tips.LIFT, home))
        assertEquals(Tips.LIFT, tips.showing)
        assertTrue(tips.isShowing(Tips.LIFT, home))
        tips.markSeen(Tips.LIFT)
        tips.dismiss(Tips.LIFT)
        today = "2026-09-27"
        assertFalse(tips.claim(Tips.LIFT, home))
        assertNull(tips.showing)
    }

    @Test
    fun aTipThatWasNotSeenComesBackAndLeavesTheDayFree() {
        val store = MemoryStore()
        val tips = TipsState(store) { "2026-09-26" }
        // Up for a moment under controls that hid again: claimed, never seen.
        assertTrue(tips.claim(Tips.PLAYER_DOUBLE_TAP, home))
        tips.dismiss(Tips.PLAYER_DOUBLE_TAP)
        assertNull(store.day)
        assertTrue(tips.claim(Tips.PLAYER_DOUBLE_TAP, home))
    }

    @Test
    fun aPageLeftWithItsTipHiddenUnderControlsDoesNotRetireIt() {
        val store = MemoryStore()
        val tips = TipsState(store) { "2026-09-26" }
        assertTrue(tips.claim(Tips.PLAYER_DOUBLE_TAP, home))
        // The controls it sat under had hidden before the page went: it was never read.
        tips.release(Tips.PLAYER_DOUBLE_TAP, home, seen = false)
        assertNull(tips.showing)
        assertNull(store.day)
        val homeAgain = Any()
        assertTrue(tips.claim(Tips.PLAYER_DOUBLE_TAP, homeAgain))
    }

    @Test
    fun noMoreThanOneTipSeenADay() {
        val store = MemoryStore()
        var today = "2026-09-26"
        val tips = TipsState(store) { today }
        assertTrue(tips.claim(Tips.LIFT, home))
        tips.markSeen(Tips.LIFT)
        tips.dismiss(Tips.LIFT)
        assertFalse(tips.claim(Tips.PINCH_GRID, home))
        today = "2026-09-27"
        assertTrue(tips.claim(Tips.PINCH_GRID, home))
    }

    @Test
    fun aTipOnScreenKeepsItsSlotAndBlocksOthers() {
        val tips = TipsState(MemoryStore()) { "2026-09-26" }
        assertTrue(tips.claim(Tips.LIFT, home))
        assertTrue(tips.claim(Tips.LIFT, home))
        assertFalse(tips.claim(Tips.SWIPE_ROW_HISTORY, downloads))
    }

    @Test
    fun theSameTipAtASecondPlaceIsNotShownTwice() {
        val tips = TipsState(MemoryStore()) { "2026-09-26" }
        assertTrue(tips.claim(Tips.SWIPE_ROW_DOWNLOADS, downloads))
        assertFalse(tips.claim(Tips.SWIPE_ROW_DOWNLOADS, home))
        assertFalse(tips.isShowing(Tips.SWIPE_ROW_DOWNLOADS, home))
        // Nor does that second place leaving take it down where it is showing.
        tips.release(Tips.SWIPE_ROW_DOWNLOADS, home)
        assertTrue(tips.isShowing(Tips.SWIPE_ROW_DOWNLOADS, downloads))
    }

    @Test
    fun aTipLeavesWithItsPageAndDoesNotComeBackWithIt() {
        val store = MemoryStore()
        var today = "2026-09-26"
        val tips = TipsState(store) { today }
        assertTrue(tips.claim(Tips.LIFT, home))
        tips.release(Tips.LIFT, home)
        assertNull(tips.showing)
        // Back on the page, a new placement of the same tip: it is retired.
        val homeAgain = Any()
        assertFalse(tips.claim(Tips.LIFT, homeAgain))
        assertFalse(tips.isShowing(Tips.LIFT, homeAgain))
        // And the slot is free for the next tip once the day allows one.
        today = "2026-09-27"
        assertTrue(tips.claim(Tips.PINCH_GRID, homeAgain))
    }

    @Test
    fun usingTheGestureRetiresATipThatNeverShowed() {
        val store = MemoryStore()
        val tips = TipsState(store) { "2026-09-26" }
        tips.markUsed(Tips.ZOOM_BACK)
        assertFalse(tips.claim(Tips.ZOOM_BACK, home))
        assertNull(store.day)
    }

    @Test
    fun usingTheGestureTakesDownTheTipAboutIt() {
        val tips = TipsState(MemoryStore()) { "2026-09-26" }
        tips.claim(Tips.PLAYER_CENTER_HOLD, home)
        tips.markUsed(Tips.PLAYER_CENTER_HOLD)
        assertNull(tips.showing)
        assertFalse(tips.isShowing(Tips.PLAYER_CENTER_HOLD, home))
    }
}
