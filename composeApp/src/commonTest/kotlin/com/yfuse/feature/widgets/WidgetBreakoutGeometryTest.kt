package com.yfuse.feature.widgets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetBreakoutGeometryTest {
    @Test
    fun portraitSubjectIsPositionedInsideReservedHeadroom() {
        val framing = breakoutFraming(600, 900, .2f, .85f)
        val scaledHeight = 1.5f
        val croppedY = -(scaledHeight - .82f) * (framing.verticalBias + 1) / 2
        val subjectY = .2f * scaledHeight + croppedY
        assertTrue(subjectY >= 0f)
        assertTrue(subjectY < .82f * framing.frameTopFraction)
        assertTrue(framing.frameTopFraction <= .5f)
    }

    @Test
    fun landscapeAndExtremeAspectRatiosStayBounded() {
        listOf(1536 to 1024, 600 to 1800, 2000 to 200, 1 to Int.MAX_VALUE).forEach { (width, height) ->
            val framing = breakoutFraming(width, height, .18f, .8f)
            assertTrue(framing.verticalBias in -1f..1f)
            assertTrue(framing.frameTopFraction in 0.18f..0.5f)
        }
    }

    @Test
    fun malformedMetadataCannotBreakLayout() {
        val fallback = BreakoutFraming(0f, .22f)
        assertEquals(fallback, breakoutFraming(0, 200, .1f, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, Float.NaN, .8f))
        assertEquals(fallback, breakoutFraming(100, 200, .1f, Float.POSITIVE_INFINITY))
    }
}
