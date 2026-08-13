package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesignSystemContractTest {

    @Test
    fun width_tiers_change_at_the_shared_breakpoints() {
        assertEquals(WindowWidthTier.Compact, windowWidthTier(0.dp))
        assertEquals(WindowWidthTier.Compact, windowWidthTier(599.dp))
        assertEquals(WindowWidthTier.Medium, windowWidthTier(600.dp))
        assertEquals(WindowWidthTier.Medium, windowWidthTier(839.dp))
        assertEquals(WindowWidthTier.Expanded, windowWidthTier(840.dp))
    }

    @Test
    fun fixed_product_emphasis_remains_readable_in_both_themes() {
        listOf(false, true).forEach { dark ->
            val palette = if (dark) DarkPalette else LightPalette
            val colors = resolveAccentColors(Brand.Primary, dark)
            assertContrastAtLeast(colors.accent, palette.background, 4.5f, "brand accent")
            assertContrastAtLeast(colors.accent, colors.container, 4.5f, "brand container")
            assertContrastAtLeast(colors.onAccent, colors.accent, 4.5f, "brand onAccent")
        }
    }

    @Test
    fun semantic_text_and_error_roles_clear_small_text_contrast() {
        listOf(LightPalette, DarkPalette).forEach { palette ->
            listOf(palette.text, palette.sub, palette.sub2, palette.body, palette.hint).forEach {
                assertContrastAtLeast(it, palette.background, 4.5f, "text role")
            }
            assertContrastAtLeast(palette.error, palette.background, 4.5f, "error")
            assertContrastAtLeast(palette.onError, palette.error, 4.5f, "onError")
            assertContrastAtLeast(
                palette.onErrorContainer,
                palette.errorContainer,
                4.5f,
                "onErrorContainer",
            )
        }
    }

    @Test
    fun typography_exposes_four_fixed_levels_with_three_weights() {
        assertEquals(26.sp, AppTypography.display.regular.fontSize)
        assertEquals(18.sp, AppTypography.section.regular.fontSize)
        assertEquals(13.sp, AppTypography.body.regular.fontSize)
        assertEquals(11.sp, AppTypography.caption.regular.fontSize)

        assertEquals(FontWeight.Normal, AppTypography.body.regular.fontWeight)
        assertEquals(FontWeight.Medium, AppTypography.body.medium.fontWeight)
        assertEquals(FontWeight.SemiBold, AppTypography.body.strong.fontWeight)
    }

    private fun assertContrastAtLeast(
        foreground: Color,
        background: Color,
        minimum: Float,
        label: String,
    ) {
        val lighter = maxOf(foreground.luminance(), background.luminance())
        val darker = minOf(foreground.luminance(), background.luminance())
        val ratio = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue(ratio >= minimum, "$label contrast was $ratio; expected at least $minimum")
    }
}
