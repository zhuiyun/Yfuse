package com.yfuse.feature.widgets

import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetBreakoutGeometryTest {
    @Test
    fun posterKeepsAFixedFourToFiveFrameWithAShortOverhang() {
        for (width in listOf(80f, 240f, 320f * BREAKOUT_CARD_WIDTH)) {
            val height = width * BREAKOUT_WINDOW_HEIGHT
            val overhang = height * BREAKOUT_FRAME_TOP
            val posterHeight = height - overhang
            assertEquals(width * 1.25f, posterHeight, .0001f)
            assertEquals(posterHeight * .18f, overhang, .0001f)
            assertTrue(overhang < posterHeight / 4)
        }
    }

    @Test
    fun moderateWhitespaceKeepsTheSubjectAndBackgroundInTheSameSourceCrop() {
        for ((width, height) in listOf(600 to 900, 900 to 600, 600 to 1800)) {
            for (top in listOf(0f, .06f, .15f, .2f)) {
                val framing = breakoutFraming(width, height, top, .95f)
                assertTrue(framing.usable)
                val scaledHeight = max(height.toFloat() / width, BREAKOUT_WINDOW_HEIGHT) * framing.scale
                val offset = -(scaledHeight - BREAKOUT_WINDOW_HEIGHT) * (framing.verticalBias + 1) / 2
                assertEquals(0f, top * scaledHeight + offset, .0001f)
                assertTrue(scaledHeight + offset >= BREAKOUT_WINDOW_HEIGHT - .0001f)
                assertTrue(framing.scale in 1f..1.35f)
            }
        }
    }

    @Test
    fun tinyOrLowSubjectsFallBackInsteadOfMagnifyingThePoster() {
        assertFalse(breakoutFraming(600, 900, .1f, .2f).usable)
        assertFalse(breakoutFraming(600, 900, .5f, .99f).usable)
        assertFalse(breakoutFraming(900, 600, .4f, .99f).usable)
    }

    @Test
    fun frameHeightDoesNotDependOnTheImageOrSubjectBounds() {
        val cases = listOf(.02f to .35f, .15f to .82f, .30f to .95f, .8f to .99f)
        for ((width, height) in listOf(600 to 900, 900 to 600, 600 to 1800)) {
            for ((top, bottom) in cases) {
                val framing = breakoutFraming(width, height, top, bottom)
                assertEquals(BREAKOUT_FRAME_TOP, framing.frameTopFraction)
                assertTrue(framing.scale.isFinite() && framing.scale <= 1.35f)
                assertTrue(framing.verticalBias in -1f..1f)
            }
        }
    }

    @Test
    fun malformedMetadataCannotEnableBreakout() {
        for ((width, height, top, bottom) in listOf(
            listOf(0f, 200f, .1f, .8f),
            listOf(100f, 200f, Float.NaN, .8f),
            listOf(100f, 200f, .1f, Float.POSITIVE_INFINITY),
            listOf(100f, 200f, .8f, .2f),
            listOf(100f, 200f, 1f, 1f),
            listOf(100f, 200f, -.1f, .8f),
            listOf(100f, 200f, .1f, 1.1f),
        )) {
            assertFalse(breakoutFraming(width.toInt(), height.toInt(), top, bottom).usable)
        }
    }
}
