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
