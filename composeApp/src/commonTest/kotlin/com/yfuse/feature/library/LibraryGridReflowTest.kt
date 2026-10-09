package com.yfuse.feature.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryGridReflowTest {
    private fun assertNear(
        expected: Float,
        actual: Float,
        message: String = "",
    ) = assertTrue(abs(expected - actual) < 0.01f, "$message expected $expected but was $actual")

    private fun assertRect(
        expected: Rect,
        actual: Rect,
    ) {
        assertNear(expected.left, actual.left, "left")
        assertNear(expected.top, actual.top, "top")
        assertNear(expected.right, actual.right, "right")
        assertNear(expected.bottom, actual.bottom, "bottom")
    }

    @Test
    fun aPosterAtRestIsDrawnWhereItIsLaidOut() {
        val drawn =
            gridReflowVisual(
                at = Offset(120f, 300f),
                width = 100f,
                height = 180f,
                start = GridReflowStart.Resting,
                remaining = 0f,
                origin = Offset(200f, 400f),
                scale = 1f,
            )
        assertRect(Rect(120f, 300f, 220f, 480f), drawn)
    }

    @Test
    fun thePinchScalesThePosterAboutTheFingers() {
        // Drawn at 0.8 about (200, 400): 80 px left of the fingers becomes 64, 100 px above becomes 80.
        val drawn =
            gridReflowVisual(
                at = Offset(120f, 300f),
                width = 100f,
                height = 180f,
                start = GridReflowStart.Resting,
                remaining = 0f,
                origin = Offset(200f, 400f),
                scale = 0.8f,
            )
        assertRect(Rect(136f, 320f, 216f, 464f), drawn)
    }

    @Test
    fun theNewLayoutStartsEachPosterExactlyWhereTheOldOneDrewIt() {
        val origin = Offset(200f, 400f)
        // Three across at 0.86 before the switch; four across at 1.14 after it, somewhere else.
        val before =
            gridReflowVisual(Offset(120f, 300f), 110f, 200f, GridReflowStart.Resting, 0f, origin, 0.86f)
        val start = gridReflowStart(before, at = Offset(60f, 520f), width = 80f, origin = origin, scale = 1.14f)
        val redrawn = gridReflowVisual(Offset(60f, 520f), 80f, 200f * 110f / 80f, start, 1f, origin, 1.14f)
        assertNear(before.left, redrawn.left, "left")
        assertNear(before.top, redrawn.top, "top")
        assertNear(before.width, redrawn.width, "width")
        // And the flow ends with the poster at its own new place.
        val home = gridReflowVisual(Offset(60f, 520f), 80f, 150f, start, 0f, origin, 1.14f)
        assertRect(gridReflowVisual(Offset(60f, 520f), 80f, 150f, GridReflowStart.Resting, 0f, origin, 1.14f), home)
    }

    @Test
    fun aSwitchDuringAFlowStartsFromWhereTheFlowHadGot() {
        val origin = Offset(0f, 0f)
        val flowing = GridReflowStart(dx = 40f, dy = -20f, scale = 1.2f)
        // Half way through its flow the poster is drawn 20 px right, 10 px up and at 1.1.
        val drawn = gridReflowVisual(Offset(100f, 100f), 50f, 75f, flowing, 0.5f, origin, 1f)
        assertRect(Rect(120f, 90f, 175f, 172.5f), drawn)
    }

    @Test
    fun nothingToFlowFromWithoutASize() {
        val before = Rect(0f, 0f, 10f, 10f)
        val noWidth = gridReflowStart(before, Offset.Zero, width = 0f, origin = Offset.Zero, scale = 1f)
        val noScale = gridReflowStart(before, Offset.Zero, width = 10f, origin = Offset.Zero, scale = 0f)
        assertEquals(GridReflowStart.Resting, noWidth)
        assertEquals(GridReflowStart.Resting, noScale)
        assertTrue(GridReflowStart.Arriving.arriving)
    }
}
