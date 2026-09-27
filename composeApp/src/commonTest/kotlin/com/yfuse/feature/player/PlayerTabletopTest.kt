package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerTabletopTest {
    @Test
    fun aHingeAcrossTheMiddleSplitsPictureAboveAndControlsBelow() {
        val split = tabletopSplit(hingeTopPx = 1_080, hingeBottomPx = 1_110, windowHeightPx = 2_200)
        assertEquals(1_080, split?.pictureBottomPx)
        assertEquals(1_110, split?.controlsTopPx)
    }

    @Test
    fun aFoldNearAnEdgeOrOutsideTheWindowDoesNotSplit() {
        assertNull(tabletopSplit(hingeTopPx = 300, hingeBottomPx = 320, windowHeightPx = 2_200))
        assertNull(tabletopSplit(hingeTopPx = 1_900, hingeBottomPx = 1_920, windowHeightPx = 2_200))
        assertNull(tabletopSplit(hingeTopPx = 0, hingeBottomPx = 20, windowHeightPx = 2_200))
        assertNull(tabletopSplit(hingeTopPx = 1_100, hingeBottomPx = 1_100, windowHeightPx = 0))
        assertNull(tabletopSplit(hingeTopPx = 1_100, hingeBottomPx = 1_000, windowHeightPx = 2_200))
    }
}
