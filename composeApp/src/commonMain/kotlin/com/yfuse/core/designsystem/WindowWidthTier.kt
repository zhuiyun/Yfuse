package com.yfuse.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Lightweight width policy that stays usable from common code and pure unit tests. */
enum class WindowWidthTier {
    Compact,
    Medium,
    Expanded,
}

object WindowWidthBreakpoints {
    val medium = 600.dp
    val foldableExpanded = 700.dp
    val expanded = 840.dp
}

/** Compact below 600dp, Medium from 600dp through 839.999dp, Expanded from 840dp. */
fun windowWidthTier(width: Dp): WindowWidthTier =
    when {
        width < WindowWidthBreakpoints.medium -> WindowWidthTier.Compact
        width < WindowWidthBreakpoints.expanded -> WindowWidthTier.Medium
        else -> WindowWidthTier.Expanded
    }

/** A short landscape phone still needs the full width for its content. */
fun useNavigationRail(
    width: Dp,
    height: Dp,
): Boolean = width >= WindowWidthBreakpoints.expanded && height >= 480.dp

/**
 * Whether the window has enough width and height for two-pane list/detail and settings
 * layouts: expanded tablets in any orientation, plus unfolded book-style foldables
 * (700dp+ wide with at least 520dp height, excluding short landscape phones).
 */
fun useDualPaneLayout(
    width: Dp,
    height: Dp,
): Boolean =
    useNavigationRail(width, height) ||
        (width >= WindowWidthBreakpoints.foldableExpanded && height >= 520.dp)
