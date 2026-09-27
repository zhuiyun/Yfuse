package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonTabsTest {
    private val lefts = floatArrayOf(0f, 100f, 260f)
    private val widths = floatArrayOf(100f, 160f, 80f)

    @Test
    fun highlightSitsOnTheSettledTab() {
        assertEquals(0f to 100f, seasonTabIndicator(0f, lefts, widths))
        assertEquals(100f to 160f, seasonTabIndicator(1f, lefts, widths))
        assertEquals(260f to 80f, seasonTabIndicator(2f, lefts, widths))
    }

    @Test
    fun highlightFollowsTheFingerBetweenTwoTabs() {
        // A quarter of the way from the second season to the third: place and width both move.
        assertEquals(140f to 140f, seasonTabIndicator(1.25f, lefts, widths))
        assertEquals(50f to 130f, seasonTabIndicator(0.5f, lefts, widths))
    }

    @Test
    fun highlightStopsAtTheEnds() {
        assertEquals(0f to 100f, seasonTabIndicator(-0.4f, lefts, widths))
        assertEquals(260f to 80f, seasonTabIndicator(2.3f, lefts, widths))
        assertEquals(0f to 0f, seasonTabIndicator(1f, FloatArray(0), FloatArray(0)))
        assertEquals(0f to 0f, seasonTabIndicator(Float.NaN, lefts, widths))
    }

    @Test
    fun tabRowScrollsToCentreTheTabItIsHeadingFor() {
        assertEquals(0, seasonTabScrollTarget(left = 0f, width = 100f, viewport = 300f, maxScroll = 400))
        assertEquals(250, seasonTabScrollTarget(left = 360f, width = 80f, viewport = 300f, maxScroll = 400))
        assertEquals(400, seasonTabScrollTarget(left = 900f, width = 80f, viewport = 300f, maxScroll = 400))
        assertEquals(0, seasonTabScrollTarget(left = 360f, width = 80f, viewport = 300f, maxScroll = 0))
    }
}
