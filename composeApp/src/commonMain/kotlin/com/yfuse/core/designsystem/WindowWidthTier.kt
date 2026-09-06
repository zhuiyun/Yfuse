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
    val expanded = 840.dp
}

/** Compact below 600dp, Medium from 600dp through 839.999dp, Expanded from 840dp. */
fun windowWidthTier(width: Dp): WindowWidthTier =
    when {
        width < WindowWidthBreakpoints.medium -> WindowWidthTier.Compact
        width < WindowWidthBreakpoints.expanded -> WindowWidthTier.Medium
        else -> WindowWidthTier.Expanded
    }

private const val NAVIGATION_RAIL_MIN_ASPECT_RATIO = 1.2f
private val NAVIGATION_RAIL_MIN_HEIGHT = 600.dp

/**
 * Whether navigation belongs at the side rather than along the bottom.
 *
 * An expanded window in landscape has spare width and scarce height, which is exactly when a
 * bottom bar costs the most and a rail costs the least. That covers a 10-inch tablet turned
 * sideways and every desktop-class window; portrait tablets keep the bottom bar, and so does a
 * landscape phone, whose short edge is too short to be an "expanded" window in any useful sense.
 */
fun useNavigationRail(
    width: Dp,
    height: Dp,
): Boolean =
    windowWidthTier(width) == WindowWidthTier.Expanded &&
        height >= NAVIGATION_RAIL_MIN_HEIGHT &&
        width > height * NAVIGATION_RAIL_MIN_ASPECT_RATIO
