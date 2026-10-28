package com.yfuse.feature.widgets

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetBreakoutGeometryTest {
    @Test
    fun portraitSubjectStartsAtTheTopOfTheReservedOverhang() {
        val framing = breakoutFraming(600, 900, .2f, .85f)
        val scaledHeight = max(1.5f, BREAKOUT_WINDOW_HEIGHT) * framing.scale
        val croppedY = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
        val subjectY = .2f * scaledHeight + croppedY
        assertEquals(0f, subjectY, .0001f)
        val frameTop = BREAKOUT_WINDOW_HEIGHT * framing.frameTopFraction
        val posterHeight = BREAKOUT_WINDOW_HEIGHT - frameTop
        assertEquals(posterHeight / 2, frameTop - subjectY, .0001f)
    }

    @Test
    fun posterKeepsItsPreviousHeightAndReservesAnotherHalfAboveIt() {
        for (cardWidth in listOf(80f, 240f, 320f * BREAKOUT_CARD_WIDTH)) {
            val height = cardWidth * BREAKOUT_WINDOW_HEIGHT
            val overhang = height * BREAKOUT_FRAME_TOP
            val posterHeight = height - overhang
            assertEquals(cardWidth * 1.25f * .86f, posterHeight, .0001f)
            assertEquals(posterHeight / 2, overhang, .0001f)
        }
    }

    @Test
    fun changingSubjectBoundsOrPosterAspectNeverChangesTheFrameHeight() {
        val samples = listOf(.02f to .35f, .15f to .82f, .30f to .95f, .45f to .8f, .8f to .99f)
        for ((width, height) in listOf(600 to 900, 900 to 600, 600 to 1800)) {
            for ((top, bottom) in samples) {
                val framing = breakoutFraming(width, height, top, bottom)
                assertEquals(1f / 3, framing.frameTopFraction)
                val scaledHeight = max(height.toFloat() / width, BREAKOUT_WINDOW_HEIGHT) * framing.scale
                val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
                // The shared source crop must still cover the entire fixed background frame.
                assertTrue(offset <= .0001f)
                assertTrue(scaledHeight + offset >= BREAKOUT_WINDOW_HEIGHT - .0001f)
                assertTrue(framing.scale.isFinite() && framing.scale >= 1f)
            }
        }
    }

    @Test
    fun subjectsWithDifferentWhitespaceHaveTheSameVisibleOverhang() {
        for ((width, height) in listOf(600 to 900, 900 to 600, 600 to 1800)) {
            for (top in listOf(0f, .06f, .15f, .2f, .3f, .4f, .8f, .95f)) {
                val framing = breakoutFraming(width, height, top, .99f)
                val scaledHeight = max(height.toFloat() / width, BREAKOUT_WINDOW_HEIGHT) * framing.scale
                val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
                val visibleTop = top * scaledHeight + offset
                val frameTop = framing.frameTopFraction * BREAKOUT_WINDOW_HEIGHT
                val posterHeight = BREAKOUT_WINDOW_HEIGHT - frameTop
                assertEquals(0f, visibleTop, .0001f)
                assertEquals(posterHeight / 2, frameTop - visibleTop, .0001f)
            }
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
    fun shortSubjectsStillFillTheOverhangAndReachThePoster() {
        val framing = breakoutFraming(600, 900, .1f, .2f)
        val scaledHeight = max(1.5f, BREAKOUT_WINDOW_HEIGHT) * framing.scale
        val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
        val top = .1f * scaledHeight + offset
        val bottom = .2f * scaledHeight + offset
        val frameTop = BREAKOUT_WINDOW_HEIGHT * framing.frameTopFraction
        assertEquals(0f, top, .0001f)
        assertTrue(bottom >= frameTop - .0001f)
        assertTrue(scaledHeight + offset >= BREAKOUT_WINDOW_HEIGHT - .0001f)
    }

    @Test
    fun malformedMetadataCannotBreakLayout() {
        val fallback = BreakoutFraming(0f)
        assertEquals(fallback, breakoutFraming(0, 200, .1f, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, Float.NaN, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, .1f, Float.POSITIVE_INFINITY))
        assertEquals(fallback, breakoutFraming(100, 200, .8f, .2f))
        assertEquals(fallback, breakoutFraming(100, 200, 1f, 1f))
        assertEquals(fallback, breakoutFraming(100, 200, -.1f, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, .1f, 1.1f))
    }
}
