package com.yfuse.feature.player

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** Keep both edges of the player controls equally clear of a notch in either orientation. */
@Composable
internal fun playerCutoutInsets(sides: WindowInsetsSides): WindowInsets {
    val cutout = WindowInsets.displayCutout
    return remember(cutout, sides) { SymmetricPlayerInsets(cutout).only(sides) }
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
