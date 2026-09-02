package com.yfuse.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationCollapseGuardTest {
    @Test
    fun `manual expansion ignores the old fling but allows the next drag`() {
        val guard = NavigationCollapseGuard()

        guard.onManualExpand()

        assertFalse(guard.acceptsScroll(userInput = false))
        assertTrue(guard.acceptsScroll(userInput = true))
        assertTrue(guard.acceptsScroll(userInput = false))
    }

    @Test
    fun `finishing the interrupted fling restores automatic collapse`() {
        val guard = NavigationCollapseGuard()

        guard.onManualExpand()
        guard.onFlingFinished()

        assertTrue(guard.acceptsScroll(userInput = false))
    }
}
