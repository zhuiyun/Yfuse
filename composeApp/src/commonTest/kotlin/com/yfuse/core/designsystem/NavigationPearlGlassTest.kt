package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NavigationPearlGlassTest {
    @Test
    fun pearlCapsuleRespectsEveryAccessibilityFallback() {
        for (reduceTransparency in listOf(false, true)) {
            for (frosted in listOf(false, true)) {
                for (supportsBlur in listOf(false, true)) {
                    assertEquals(
                        supportsBlur && !reduceTransparency && !frosted,
                        useLiquidNavigationMaterial(reduceTransparency, frosted, supportsBlur),
                    )
                }
            }
        }
    }

    @Test
    fun selectionRetainsMostOfTheBackdropInBothThemes() {
        for (dark in listOf(false, true)) {
            val ink = navigationSelectionInk(dark, Brand.Primary)
            for (color in listOf(ink.top, ink.middle, ink.bottom)) {
                assertTrue(color.alpha > 0f)
                assertTrue(color.alpha < 0.25f, "Selection must retain most of its backdrop")
            }
            assertNotEquals(ink.top.copy(alpha = 1f), ink.bottom.copy(alpha = 1f))
        }
    }

    @Test
    fun pearlEndsAndCenterFollowTheChosenAccent() {
        val blueAccent = Color(0xFF3D64C9)
        val greenAccent = Color(0xFF208054)
        val blue = navigationSelectionInk(false, blueAccent)
        val green = navigationSelectionInk(false, greenAccent)
        assertNotEquals(blue.top, green.top)
        assertNotEquals(blue.middle, green.middle)
        assertNotEquals(blue.bottom, green.bottom)
        assertEquals(blueAccent, blue.middle.copy(alpha = 1f))
        assertEquals(greenAccent, green.middle.copy(alpha = 1f))
        assertEquals(blue.middle.alpha, green.middle.alpha)
    }

    @Test
    fun darkSelectionUsesQuieterHighlights() {
        val light = navigationSelectionInk(false, Brand.Primary)
        val dark = navigationSelectionInk(true, Brand.Primary)
        assertTrue(dark.rimNear < light.rimNear)
        assertTrue(dark.rimSide < light.rimSide)
        assertTrue(dark.rimFar < light.rimFar)
    }

    @Test
    fun dockAndSearchKeepTheirSharedGlassLightAndTranslucent() {
        for (ink in listOf(NavigationGlassInk.Light, NavigationGlassInk.Dark)) {
            assertTrue(ink.tintTop.alpha < 0.4f)
            assertTrue(ink.tintBottom.alpha < 0.4f)
            assertTrue(ink.rimSide < ink.rimNear)
            assertTrue(ink.sheenAlpha <= 0.10f)
        }
        assertTrue(NavigationGlassRim <= 0.8.dp)
    }

    @Test
    fun refractionStaysNearTheEdgeInsteadOfMakingADoubleCapsule() {
        assertTrue(NavigationGlassRefraction.strength <= 4.dp)
        assertTrue(NavigationGlassRefraction.edgeX <= 0.12f)
        assertTrue(NavigationGlassRefraction.edgeY <= 0.18f)
        assertTrue(NavigationGlassBlurRadius >= 16.dp)
    }
}
