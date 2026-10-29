package com.yfuse.feature.widgets

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetHomeLayoutTest {
    @Test
    fun singleColumnContentKeepsMarginsAndStopsGrowingOnExtraWideWindows() {
        for (width in listOf(393, 700, 840, 1200, 1600, 2400)) {
            val inset = widgetHomeHorizontalPadding(width.dp)
            val contentWidth = width.dp - inset * 2
            assertTrue(inset >= 16.dp)
            assertTrue(contentWidth > 0.dp && contentWidth <= 1200.dp)
            if (width in 600..1200) assertTrue(contentWidth >= width.dp - 64.dp)
        }
        assertEquals(1200.dp, 2400.dp - widgetHomeHorizontalPadding(2400.dp) * 2)
    }

    @Test
    fun widePagersRevealNeighboursWithoutMakingPortraitPostersTallerThanTheViewport() {
        for (width in listOf(700, 1200, 1600)) {
            for (font in listOf(1f, 2f)) {
                val budget = widgetHomeGalleryHeight(width.dp, 640.dp, 96.dp, font)
                val poster = widgetPagerCardWidth(width.dp, false, true, budget)
                val hero = widgetPagerCardWidth(width.dp, false, false, budget)
                assertTrue(poster / .667f <= budget + .01.dp)
                assertTrue(poster <= 280.dp && hero <= 480.dp)
                assertTrue((width.dp - hero) / 2 >= 100.dp)
            }
        }
        assertEquals(289.dp, widgetPagerCardWidth(393.dp, false, true, Dp.Infinity))
    }

    @Test
    fun expandedGalleryUsesTheRowWhileItsArtworkKeepsTheSameProportions() {
        val budget = 280.dp
        val artwork = widgetGalleryWidth(1136.dp, false, budget, .91f)
        val viewport = widgetGalleryViewportWidth(1136.dp, artwork)
        assertEquals(1136.dp, viewport)
        assertTrue(artwork * .91f <= budget + .01.dp)
        assertTrue((viewport - artwork) / 2 >= 176.dp)
        assertEquals(320.dp, widgetGalleryViewportWidth(320.dp, 320.dp))
    }

    @Test
    fun landscapeStageLeavesRoomForTitlesActionsAndTheActualDock() {
        val height = widgetHomeGalleryHeight(1200.dp, 640.dp, 96.dp, 1f)
        assertEquals(412.dp, height)
        assertTrue(height + 132.dp + 96.dp <= 640.dp)
        assertTrue(widgetHomeGalleryHeight(1200.dp, 640.dp, 160.dp, 2f) < height)
        assertEquals(Dp.Infinity, widgetHomeGalleryHeight(393.dp, 700.dp, 96.dp, 1f))
        assertEquals(420.dp, widgetHomeGalleryHeight(1024.dp, 1300.dp, 96.dp, 1f))
        assertTrue(widgetHomeGalleryHeight(700.dp, 640.dp, 96.dp, 1f).value.isFinite())
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
