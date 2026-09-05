package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NavigationPearlGlassTest {
    @Test
    fun lensRequiresBlurAndRespectsBothUserFallbacks() {
        for (reduceTransparency in listOf(false, true)) {
            for (frosted in listOf(false, true)) {
                for (supportsBlur in listOf(false, true)) {
                    assertEquals(
                        supportsBlur && !reduceTransparency && !frosted,
                        navigationLensEnabled(reduceTransparency, frosted, supportsBlur),
                    )
                }
            }
        }
        assertTrue(navigationLensEnabled(false, false, true))
        assertFalse(navigationLensEnabled(false, false, false))
    }

    @Test
    fun selectionRetainsBackdropInsteadOfAddingAWhitePlate() {
        for (dark in listOf(false, true)) {
            val ink = navigationSelectionInk(dark, Brand.Primary)
            for (color in listOf(ink.rose, ink.center, ink.blue)) {
                assertTrue(color.alpha > 0f)
                // Worst case: the strongest white sheen over the strongest pearl stop.
                val combinedAlpha = 1f - (1f - color.alpha) * (1f - ink.sheen)
                assertTrue(combinedAlpha < 0.38f, "Selection must retain most of its backdrop")
            }
        }
    }

    @Test
    fun pearlTintFollowsTheChosenAccent() {
        val blue = navigationSelectionInk(false, Color(0xFF3D64C9))
        val green = navigationSelectionInk(false, Color(0xFF208054))
        assertNotEquals(blue.rose, green.rose)
        assertNotEquals(blue.center, green.center)
        assertNotEquals(blue.blue, green.blue)
        assertEquals(blue.center.alpha, green.center.alpha)
    }

    @Test
    fun darkSelectionUsesQuieterHighlights() {
        val light = navigationSelectionInk(false, Brand.Primary)
        val dark = navigationSelectionInk(true, Brand.Primary)
        assertTrue(dark.sheen < light.sheen)
        assertTrue(dark.rimTop < light.rimTop)
        assertTrue(dark.rimSide < light.rimSide)
        assertTrue(dark.rimBottom < light.rimBottom)
    }

    @Test
    fun dockAndSearchMaterialHaveNoBroadInnerRing() {
        for (ink in listOf(NavigationGlassInk.Light, NavigationGlassInk.Dark)) {
            assertEquals(0f, ink.glow)
            assertEquals(0f, ink.hairline)
            assertTrue(ink.tintTop.alpha < 0.4f)
            assertTrue(ink.tintBottom.alpha < 0.4f)
            assertTrue(ink.rimSide < ink.rimNear)
        }
        assertTrue(NavigationGlassRim <= 0.8.dp)
        assertTrue(NavigationSelectionRim <= 0.8.dp)
    }

    @Test
    fun refractionStaysNearTheEdgeInsteadOfMakingADoubleCapsule() {
        assertTrue(NavigationGlassRefraction.strength <= 4.dp)
        assertTrue(NavigationGlassRefraction.edgeX <= 0.12f)
        assertTrue(NavigationGlassRefraction.edgeY <= 0.18f)
        assertTrue(NavigationGlassBlurRadius >= 16.dp)
    }
}
