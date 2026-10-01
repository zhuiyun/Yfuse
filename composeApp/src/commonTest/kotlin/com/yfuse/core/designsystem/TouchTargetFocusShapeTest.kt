package com.yfuse.core.designsystem

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TouchTargetFocusShapeTest {
    private val density = Density(1f)

    private fun TouchTargetFocusShape.outline(slot: Size): Outline = createOutline(slot, LayoutDirection.Ltr, density)

    @Test
    fun aChipShorterThanItsSlotIsOutlinedWhereItIsDrawn() {
        // Wider than the floor, 34 tall: the slot grows only in height and centres the pill.
        val chip = TouchTargetFocusShape(RectangleShape)
        chip.control = IntSize(80, 34)
        assertEquals(Rect(0f, 7f, 80f, 41f), chip.outline(Size(80f, 48f)).bounds)
    }

    @Test
    fun aSmallRoundKeyKeepsItsOwnCircleInsideTheSlot() {
        val key = TouchTargetFocusShape(CircleShape)
        key.control = IntSize(38, 38)
        val outline = assertIs<Outline.Rounded>(key.outline(Size(48f, 48f)))
        assertEquals(Rect(5f, 5f, 43f, 43f), outline.bounds)
        assertEquals(19f, outline.roundRect.topLeftCornerRadius.x, 0.001f)
    }

    @Test
    fun anOddRemainderLandsOnThePixelTheSlotPlacesItOn() {
        // touchTarget places the control at (48 - 33) / 2 = 7, in whole pixels.
        val key = TouchTargetFocusShape(RectangleShape)
        key.control = IntSize(33, 33)
        assertEquals(Rect(7f, 7f, 40f, 40f), key.outline(Size(48f, 48f)).bounds)
    }

    @Test
    fun aControlNotYetMeasuredOrAsLargeAsItsSlotIsTheSlot() {
        val shape = TouchTargetFocusShape(RectangleShape)
        assertEquals(Rect(0f, 0f, 48f, 48f), shape.outline(Size(48f, 48f)).bounds)
        shape.control = IntSize(120, 60)
        assertEquals(Rect(0f, 0f, 120f, 60f), shape.outline(Size(120f, 60f)).bounds)
    }
}
