package com.yfuse.feature.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Keep both edges of the player controls equally clear of cutouts and system navigation bars. */
@Composable
internal fun playerCutoutInsets(sides: WindowInsetsSides): WindowInsets {
    val cutout = WindowInsets.displayCutout
    val navBars = WindowInsets.navigationBars
    return remember(cutout, navBars, sides) {
        SymmetricPlayerInsets(cutout.union(navBars)).only(sides)
    }
}

/** Read the live inset values during layout, including while the device rotates. */
private class SymmetricPlayerInsets(
    private val source: WindowInsets,
) : WindowInsets by source {
    override fun getLeft(
        density: Density,
        layoutDirection: LayoutDirection,
    ): Int = maxOf(source.getLeft(density, layoutDirection), source.getRight(density, layoutDirection))

    override fun getRight(
        density: Density,
        layoutDirection: LayoutDirection,
    ): Int = getLeft(density, layoutDirection)
}
