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

private val NavigationRailMinWidth = 1_200.dp
private const val NavigationRailMinAspectRatio = 1.75f

/**
 * Whether navigation belongs at the side rather than along the bottom.
 *
 * A normal tablet is roughly 4:3, 3:2 or 16:10. Even in landscape, putting the primary tabs on
 * its short edge makes the controls feel pinned to the wrong side and steals content width. Keep
 * those shapes on the bottom edge. A rail is reserved for genuinely desktop-like, extra-wide
 * windows where vertical room is scarce and the side edge is the more useful place for navigation.
 */
fun useNavigationRail(
    width: Dp,
    height: Dp,
): Boolean =
    windowWidthTier(width) == WindowWidthTier.Expanded &&
        width >= NavigationRailMinWidth &&
        width > height * NavigationRailMinAspectRatio
