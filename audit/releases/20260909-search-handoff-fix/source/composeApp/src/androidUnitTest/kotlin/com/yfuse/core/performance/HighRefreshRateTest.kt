package com.yfuse.core.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HighRefreshRateTest {
    @Test
    fun choosesHighestRefreshAtCurrentResolution() {
        val selected =
            selectHighRefreshRateMode(
                currentWidth = 1080,
                currentHeight = 2400,
                modes =
                    listOf(
                        UiDisplayMode(1, 1080, 2400, 60f),
                        UiDisplayMode(2, 1080, 2400, 90f),
                        UiDisplayMode(3, 1080, 2400, 120f),
                        UiDisplayMode(4, 1440, 3200, 144f),
                    ),
            )

        assertEquals(3, selected?.modeId)
        assertEquals(120f, selected?.refreshRate)
    }

    @Test
    fun leavesSixtyHertzPanelsToSystem() {
        val selected =
            selectHighRefreshRateMode(
                currentWidth = 1080,
                currentHeight = 2400,
                modes = listOf(UiDisplayMode(1, 1080, 2400, 60f)),
            )

        assertNull(selected)
    }

    @Test
    fun neverTradesResolutionForRefreshRate() {
        val selected =
            selectHighRefreshRateMode(
                currentWidth = 1440,
                currentHeight = 3200,
                modes =
                    listOf(
                        UiDisplayMode(1, 1440, 3200, 60f),
                        UiDisplayMode(2, 1080, 2400, 120f),
                    ),
            )

        assertNull(selected)
    }
}
