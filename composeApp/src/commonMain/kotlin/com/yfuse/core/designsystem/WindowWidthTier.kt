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
fun windowWidthTier(width: Dp): WindowWidthTier = when {
    width < WindowWidthBreakpoints.medium -> WindowWidthTier.Compact
    width < WindowWidthBreakpoints.expanded -> WindowWidthTier.Medium
    else -> WindowWidthTier.Expanded
}

/**
 * Whether navigation belongs at the side rather than along the bottom.
 *
 * Width alone was the wrong test. A rail earns its place when the window is *short and* wide:
 * there the bottom edge is scarce vertical room and the thumbs already rest at the sides. A
 * tablet held upright is wide too — 840dp is portrait for anything 11" and up — but it has
 * height to spare and its bottom edge is exactly where a thumb sits, so it was getting a side
 * rail in both orientations and a bottom bar in neither.
 */
fun useNavigationRail(width: Dp, height: Dp): Boolean =
    windowWidthTier(width) == WindowWidthTier.Expanded && width > height
