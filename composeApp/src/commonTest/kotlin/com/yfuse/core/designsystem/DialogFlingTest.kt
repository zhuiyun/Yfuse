package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogFlingTest {
    private val threshold = 96f

    @Test
    fun aPanelPushedAwayLeavesAtTheSpeedItWasLetGo() {
        assertEquals(1_500f, dialogFlingSpeed(1_500f, threshold), 0.01f)
        assertEquals(3_000f, dialogFlingSpeed(3_000f, threshold), 0.01f)
        assertTrue(dialogFlingSpeed(3_000f, threshold) > dialogFlingSpeed(1_500f, threshold))
    }

    @Test
    fun aSlowPushPastTheLineStillLeaves() {
        // Dragged past the commit point and let go almost still: it drifts off rather than hanging.
        val drift = dialogFlingSpeed(0f, threshold)
        assertTrue(drift > 0f)
        assertEquals(drift, dialogFlingSpeed(-400f, threshold), 0.01f)
        assertEquals(drift, dialogFlingSpeed(Float.NaN, threshold), 0.01f)
    }

    @Test
    fun anImplausibleFlickIsHeldToAFastExitAndABrokenReadingToADrift() {
        val fastest = dialogFlingSpeed(1e9f, threshold)
        assertTrue(fastest < 1e9f)
        assertEquals(fastest, dialogFlingSpeed(2e9f, threshold), 0.01f)
        assertEquals(dialogFlingSpeed(0f, threshold), dialogFlingSpeed(Float.POSITIVE_INFINITY, threshold), 0.01f)
        assertEquals(0f, dialogFlingSpeed(1_500f, 0f), 0.01f)
    }
}
