package com.yfuse.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pure-value coverage for the three new shared components (`Chip.kt`, `SectionHeader.kt`,
 * `RecommendBadge.kt`). None of them have separable non-Composable logic beyond what
 * [DesignSystemContractTest] already exercises (`selectionColor`, contrast-checked accent
 * roles), so this focuses on the two things that are: the typography token this pass added,
 * and the fixed colour pairing [RecommendBadge] relies on for its accessibility fix.
 */
class ChipSectionHeaderBadgeTest {
    @Test
    fun reading_variants_widen_line_height_but_keep_the_role_s_size_and_weight() {
        // body.reading: same 13sp/regular as body.regular, wider line height for running copy.
        assertEquals(AppTypography.body.regular.fontSize, AppTypography.body.reading.fontSize)
        assertEquals(AppTypography.body.regular.fontWeight, AppTypography.body.reading.fontWeight)
        assertEquals(20.8.sp, AppTypography.body.reading.lineHeight)
        assertNotEquals(AppTypography.body.regular.lineHeight, AppTypography.body.reading.lineHeight)

        // caption.reading: same 11sp/regular as caption.regular, wider line height.
        assertEquals(AppTypography.caption.regular.fontSize, AppTypography.caption.reading.fontSize)
        assertEquals(AppTypography.caption.regular.fontWeight, AppTypography.caption.reading.fontWeight)
        assertEquals(17.sp, AppTypography.caption.reading.lineHeight)
        assertNotEquals(AppTypography.caption.regular.lineHeight, AppTypography.caption.reading.lineHeight)

        // Roles that never carry paragraph-length text fall back to `regular` untouched.
        assertEquals(AppTypography.display.regular.lineHeight, AppTypography.display.reading.lineHeight)
        assertEquals(AppTypography.display.regular.fontSize, AppTypography.display.reading.fontSize)
        assertEquals(AppTypography.section.regular.lineHeight, AppTypography.section.reading.lineHeight)
        assertEquals(AppTypography.section.regular.fontSize, AppTypography.section.reading.fontSize)
    }

    @Test
    fun recommend_badge_ink_clears_aa_contrast_on_its_fixed_warning_fill() {
        // RecommendBadge is deliberately opaque so this pairing holds regardless of the
        // surrounding theme or card colour — see its kdoc for the bug this replaced
        // (~1.8:1 for dark-amber text on a 30%-alpha amber fill in dark mode).
        assertContrastAtLeast(RecommendBadgeInk, Semantic.Warning, 4.5f, "RecommendBadge ink on fill")
    }

    @Test
    fun decorative_tints_ramp_has_no_duplicate_colour() {
        assertEquals(6, DecorativeTints.ramp.toSet().size, "ramp should be 6 distinct colours")
        val named =
            listOf(
                DecorativeTints.teal,
                DecorativeTints.coral,
                DecorativeTints.amber,
                DecorativeTints.plum,
                DecorativeTints.emerald,
                DecorativeTints.lavender,
            )
        assertEquals(6, named.toSet().size, "named hues should be 6 distinct colours")
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
