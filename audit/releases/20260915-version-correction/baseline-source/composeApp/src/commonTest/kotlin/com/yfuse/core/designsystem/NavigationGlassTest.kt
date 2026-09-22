package com.yfuse.core.designsystem

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationGlassTest {
    @Test
    fun reduced_transparency_frosted_and_unsupported_platforms_keep_the_fallback() {
        listOf(false, true).forEach { reduceTransparency ->
            listOf(false, true).forEach { frosted ->
                listOf(false, true).forEach { blurSupported ->
                    assertEquals(
                        blurSupported && !reduceTransparency && !frosted,
                        useLiquidNavigationMaterial(reduceTransparency, frosted, blurSupported),
                    )
                }
            }
        }
    }

    @Test
    fun selection_remains_translucent_in_both_themes() {
        listOf(false, true).forEach { dark ->
            val ink = navigationSelectionInk(dark, Color(0xFF3867C8))
            listOf(ink.top, ink.middle, ink.bottom).forEach { color ->
                assertTrue(color.alpha in 0.08f..0.30f)
            }
            assertTrue(ink.rimNear <= 0.40f)
            assertTrue(ink.rimSide <= 0.12f)
            assertTrue(ink.rimFar < ink.rimNear)
        }
    }

    @Test
    fun selection_uses_the_current_accent_instead_of_a_fixed_blue_plate() {
        val warmAccent = Color(0xFFA4433C)
        val coolAccent = Color(0xFF3664BF)
        listOf(false, true).forEach { dark ->
            val warm = navigationSelectionInk(dark, warmAccent)
            val cool = navigationSelectionInk(dark, coolAccent)
            assertEquals(warmAccent, warm.middle.copy(alpha = 1f))
            assertEquals(coolAccent, cool.middle.copy(alpha = 1f))
            assertNotEquals(warm.top, cool.top)
            assertNotEquals(warm.bottom, cool.bottom)
        }
    }

    @Test
    fun pane_keeps_backdrop_colour_visible_in_both_themes() {
        listOf(NavigationGlassInk.Light, NavigationGlassInk.Dark).forEach { ink ->
            assertTrue(ink.tintTop.alpha < 0.50f)
            assertTrue(ink.tintBottom.alpha < 0.50f)
            assertTrue(ink.sheenAlpha <= 0.10f)
            assertTrue(ink.rimSide < ink.rimNear)
        }
    }

    @Test
    fun fine_rim_and_shallow_refraction_do_not_recreate_the_wide_inner_bevel() {
        assertTrue(NavigationGlassRim <= 1.dp)
        assertTrue(NavigationGlassRefraction.strength <= 8.dp)
        assertTrue(NavigationGlassRefraction.edgeY <= 0.25f)
        assertEquals(18.dp, NavigationGlassBlurRadius)
    }

    @Test
    fun rim_is_inset_so_its_outside_edge_stays_inside_the_selected_capsule() {
        val rect = Rect(10f, 4f, 80f, 58f)
        assertEquals(Rect(10.5f, 4.5f, 79.5f, 57.5f), navigationLensRimRect(rect, 1f))
    }

    @Test
    fun invalid_or_collapsed_bounds_never_create_an_invalid_gradient() {
        assertNull(navigationLensRimRect(Rect(0f, 0f, 0f, 10f), 1f))
        assertNull(navigationLensRimRect(Rect(10f, 0f, 0f, 10f), 1f))
        assertNull(navigationLensRimRect(Rect(0f, 0f, Float.NaN, 10f), 1f))
        assertNull(navigationLensRimRect(Rect(0f, 0f, Float.POSITIVE_INFINITY, 10f), 1f))
        assertNull(navigationLensRimRect(Rect(0f, 0f, 10f, 10f), 0f))
        assertNull(navigationLensRimRect(Rect(0f, 0f, 10f, 10f), Float.NaN))
        assertNull(navigationLensRimRect(Rect(0f, 0f, 1f, 1f), 1f))
    }

    @Test
    fun selection_fade_clamps_overshoot_and_ignores_non_finite_values() {
        assertEquals(0f, navigationSelectionAlpha(-0.5f))
        assertEquals(0f, navigationSelectionAlpha(0f))
        assertEquals(0.5f, navigationSelectionAlpha(0.5f))
        assertEquals(1f, navigationSelectionAlpha(1.5f))
        assertEquals(0f, navigationSelectionAlpha(Float.NaN))
        assertEquals(0f, navigationSelectionAlpha(Float.POSITIVE_INFINITY))
        assertEquals(0f, navigationSelectionAlpha(Float.NEGATIVE_INFINITY))
    }
}
