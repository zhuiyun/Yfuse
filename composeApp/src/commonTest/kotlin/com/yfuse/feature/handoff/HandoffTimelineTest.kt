package com.yfuse.feature.handoff

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HandoffTimelineTest {
    @Test
    fun alternateCopiesRejectDifferentCutsAndUnknownRuntime() {
        assertFalse(compatibleHandoffTimeline(7_200_000, 7_500_000, false))
        assertFalse(compatibleHandoffTimeline(7_200_000, 0, false))
        assertFalse(compatibleHandoffTimeline(0, 7_200_000, false))
        assertTrue(compatibleHandoffTimeline(7_200_000, 7_210_000, false))
    }

    @Test
    fun sameVersionCanUseThePlayersMoreAccurateDuration() {
        assertTrue(compatibleHandoffTimeline(7_200_000, 0, true))
        assertTrue(compatibleHandoffTimeline(7_200_000, 7_250_000, true))
    }

    @Test
    fun shortClipsDoNotInheritAFilmsTolerance() {
        assertFalse(compatibleHandoffTimeline(30_000, 50_000, false))
        assertTrue(compatibleHandoffTimeline(30_000, 31_000, false))
    }
}
