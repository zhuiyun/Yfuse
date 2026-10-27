package com.yfuse.feature.widgets

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetBreakoutGeometryTest {
    @Test
    fun portraitSubjectIsPositionedInsideReservedHeadroom() {
        val framing = breakoutFraming(600, 900, .2f, .85f)
        val scaledHeight = 1.5f * framing.scale
        val croppedY = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
        val subjectY = .2f * scaledHeight + croppedY
        assertTrue(subjectY >= 0f)
        assertTrue(subjectY < BREAKOUT_WINDOW_HEIGHT * framing.frameTopFraction)
        assertTrue(framing.frameTopFraction <= .5f)
    }

    @Test
    fun portraitPosterKeepsATallStageAndARealProtrusionAboveTheFixedFrame() {
        assertEquals(1.25f, BREAKOUT_WINDOW_HEIGHT)
        val framing = breakoutFraming(600, 900, .2f, .85f)
        val scaledHeight = 1.5f * framing.scale
        val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
        val subjectTop = .2f * scaledHeight + offset
        val frameTop = framing.frameTopFraction * BREAKOUT_WINDOW_HEIGHT
        assertTrue(frameTop - subjectTop >= BREAKOUT_WINDOW_HEIGHT * .08f)
        assertTrue((1f - framing.frameTopFraction) * BREAKOUT_WINDOW_HEIGHT >= .8f)
        // Keep a portrait hero while limiting the empty band before its fixed background.
        val height = 320f * BREAKOUT_CARD_WIDTH * BREAKOUT_WINDOW_HEIGHT
        assertTrue(height in 340f..360f)
        assertTrue(height * BREAKOUT_FRAME_TOP < 50f)
    }

    @Test
    fun changingSubjectBoundsOrPosterAspectNeverChangesTheFrameHeight() {
        val samples = listOf(.02f to .35f, .15f to .82f, .30f to .95f, .45f to .8f, .8f to .99f)
        for ((width, height) in listOf(600 to 900, 900 to 600, 600 to 1800)) {
            for ((top, bottom) in samples) {
                val framing = breakoutFraming(width, height, top, bottom)
                assertEquals(.14f, framing.frameTopFraction)
                val scaledHeight = max(height.toFloat() / width, BREAKOUT_WINDOW_HEIGHT) * framing.scale
                val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
                // The shared source crop must still cover the entire fixed background frame.
                assertTrue(offset <= .0001f)
                assertTrue(scaledHeight + offset >= BREAKOUT_WINDOW_HEIGHT - .0001f)
                assertTrue(framing.scale in 1f..1.6001f)
            }
        }
    }

    @Test
    fun uprightSubjectsUseTheSameHeadroomWithoutMovingTheBackgroundFrame() {
        for (top in listOf(.06f, .15f, .2f, .3f, .4f)) {
            val framing = breakoutFraming(600, 900, top, .9f)
            val scaledHeight = 1.5f * framing.scale
            val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
            val visibleTop = top * scaledHeight + offset
            assertEquals(BREAKOUT_WINDOW_HEIGHT * .045f, visibleTop, .0001f)
            assertTrue(visibleTop < framing.frameTopFraction * BREAKOUT_WINDOW_HEIGHT)
        }
    }

    @Test
    fun landscapeAndExtremeAspectRatiosStayBounded() {
        listOf(1536 to 1024, 600 to 1800, 2000 to 200, 1 to Int.MAX_VALUE).forEach { (width, height) ->
            val framing = breakoutFraming(width, height, .18f, .8f)
            assertTrue(framing.verticalBias in -1f..1f)
            assertTrue(framing.frameTopFraction in BREAKOUT_FRAME_TOP..0.5f)
        }
    }

    @Test
    fun malformedMetadataCannotBreakLayout() {
        val fallback = BreakoutFraming(0f)
        assertEquals(fallback, breakoutFraming(0, 200, .1f, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, Float.NaN, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, .1f, Float.POSITIVE_INFINITY))
    }
}
