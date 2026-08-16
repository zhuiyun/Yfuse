package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerSkipCoordinatorTest {
    @Test
    fun `movie does not expose intro or outro skipping`() {
        assertFalse(skipSegmentsAvailableFor(null))
        assertFalse(skipSegmentsAvailableFor(""))
    }

    @Test
    fun `episode keeps intro and outro skipping`() {
        assertTrue(skipSegmentsAvailableFor("series-1"))
    }

    @Test
    fun `manual skip prompt follows playback control visibility`() {
        assertTrue(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = null,
                controlsVisible = true,
            ),
        )
        assertFalse(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = null,
                controlsVisible = false,
            ),
        )
        assertFalse(
            shouldShowManualSkipPill(
                segmentLabel = "跳过片头",
                countdownSeconds = 3,
                controlsVisible = true,
            ),
        )
    }
}
