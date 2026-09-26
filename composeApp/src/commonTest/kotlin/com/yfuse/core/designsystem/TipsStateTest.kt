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

    @Test
    fun aTipShowsOnceAndRetiresTheMomentItIsShown() {
        val store = MemoryStore()
        var today = "2026-09-26"
        val tips = TipsState(store) { today }
        assertTrue(tips.claim(Tips.LIFT))
        assertEquals(Tips.LIFT, tips.showing)
        tips.dismiss(Tips.LIFT)
        today = "2026-09-27"
        assertFalse(tips.claim(Tips.LIFT))
        assertNull(tips.showing)
    }

    @Test
    fun noMoreThanOneTipADay() {
        val store = MemoryStore()
        var today = "2026-09-26"
        val tips = TipsState(store) { today }
        assertTrue(tips.claim(Tips.LIFT))
        tips.dismiss(Tips.LIFT)
        assertFalse(tips.claim(Tips.PINCH_GRID))
        today = "2026-09-27"
        assertTrue(tips.claim(Tips.PINCH_GRID))
    }

    @Test
    fun aTipOnScreenKeepsItsSlotAndBlocksOthers() {
        val tips = TipsState(MemoryStore()) { "2026-09-26" }
        assertTrue(tips.claim(Tips.LIFT))
        assertTrue(tips.claim(Tips.LIFT))
        assertFalse(tips.claim(Tips.SWIPE_ROW))
    }

    @Test
    fun usingTheGestureRetiresATipThatNeverShowed() {
        val store = MemoryStore()
        val tips = TipsState(store) { "2026-09-26" }
        tips.markUsed(Tips.ZOOM_BACK)
        assertFalse(tips.claim(Tips.ZOOM_BACK))
        assertNull(store.day)
    }

    @Test
    fun usingTheGestureTakesDownTheTipAboutIt() {
        val tips = TipsState(MemoryStore()) { "2026-09-26" }
        tips.claim(Tips.PLAYER_CENTER_HOLD)
        tips.markUsed(Tips.PLAYER_CENTER_HOLD)
        assertNull(tips.showing)
    }
}
