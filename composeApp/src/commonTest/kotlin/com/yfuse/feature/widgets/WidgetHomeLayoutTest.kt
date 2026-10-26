package com.yfuse.feature.widgets

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetHomeLayoutTest {
    @Test
    fun tabletColumnsUseAvailableWidthAndRespectLargeTextAndSplitWindows() {
        assertEquals(1, widgetHomeColumns(393.dp, 1f))
        assertEquals(1, widgetHomeColumns(700.dp, 1f))
        assertEquals(1, widgetHomeColumns(839.dp, 1f))
        assertEquals(2, widgetHomeColumns(840.dp, 1f))
        assertEquals(2, widgetHomeColumns(1200.dp, 1f))
        assertEquals(3, widgetHomeColumns(1600.dp, 1f))
        assertEquals(1, widgetHomeColumns(1024.dp, 2f))
        assertEquals(2, widgetHomeColumns(1800.dp, 2f))
    }

    @Test
    fun landscapeStageLeavesRoomForTitlesActionsAndTheActualDock() {
        val height = widgetHomeGalleryHeight(1200.dp, 640.dp, 96.dp, 1f)
        assertEquals(412.dp, height)
        assertTrue(height + 132.dp + 96.dp <= 640.dp)
        assertTrue(widgetHomeGalleryHeight(1200.dp, 640.dp, 160.dp, 2f) < height)
        assertEquals(Dp.Infinity, widgetHomeGalleryHeight(393.dp, 700.dp, 96.dp, 1f))
        assertEquals(420.dp, widgetHomeGalleryHeight(1024.dp, 1300.dp, 96.dp, 1f))
    }

    @Test
    fun everyGalleryFitsTheSameHeightBudgetWithoutStretchingItsArtwork() {
        val budget = 280.dp
        val prism = widgetGalleryWidth(572.dp, false, budget, .91f)
        val ribbon = widgetGalleryWidth(572.dp, false, budget, .76f)
        val kinetic = widgetGalleryWidth(572.dp, false, budget, .79f, 48.dp, 48.dp)
        val orbit = widgetGalleryWidth(572.dp, false, budget, .8f, 32.dp, 24.dp)
        assertTrue(prism * .91f <= budget + .01.dp)
        assertTrue(ribbon * .76f <= budget + .01.dp)
        assertTrue((kinetic - 48.dp) * .79f + 48.dp <= budget + .01.dp)
        assertTrue((orbit - 32.dp) * .8f + 24.dp <= budget + .01.dp)
        assertEquals(320.dp, widgetGalleryWidth(320.dp, false, Dp.Infinity, .91f))
        assertEquals(340.dp, widgetGalleryWidth(572.dp, true, Dp.Infinity, .91f))
    }
}
