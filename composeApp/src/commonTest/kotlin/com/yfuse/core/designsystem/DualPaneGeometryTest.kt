package com.yfuse.core.designsystem

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DualPaneGeometryTest {
    @Test
    fun bookModeLocksSplitToVerticalHingeAndAppliesHingeGap() {
        val geometry =
            resolveDualPaneGeometry(
                posture =
                    WindowPosture(
                        isBookMode = true,
                        verticalHingeFraction = 0.5f,
                        hingeThicknessDp = 12.dp,
                    ),
                foldableMedium = true,
            )
        assertEquals(0.5f, geometry.defaultFraction)
        assertEquals(12.dp, geometry.hingeGap)
        assertTrue(geometry.bookLocked)
    }

    @Test
    fun tabletopHorizontalHingeDoesNotCreateHorizontalPaneGap() {
        val geometry =
            resolveDualPaneGeometry(
                posture =
                    WindowPosture(
                        isTabletop = true,
                        isBookMode = false,
                        horizontalHingeFraction = 0.5f,
                        hingeThicknessDp = 16.dp,
                    ),
                foldableMedium = false,
            )
        assertEquals(0.45f, geometry.defaultFraction)
        assertEquals(0.dp, geometry.hingeGap)
        assertFalse(geometry.bookLocked)
    }

    @Test
    fun flatTabletUsesDefaultSplitAndAllowsDragging() {
        val medium = resolveDualPaneGeometry(WindowPosture.Flat, foldableMedium = true)
        val expanded = resolveDualPaneGeometry(WindowPosture.Flat, foldableMedium = false)
        assertEquals(0.42f, medium.defaultFraction)
        assertEquals(0.45f, expanded.defaultFraction)
        assertEquals(0.dp, medium.hingeGap)
        assertFalse(medium.bookLocked)
        assertFalse(expanded.bookLocked)
    }
}
