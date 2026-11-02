package com.yfuse.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val LocalCompactPlayerText = staticCompositionLocalOf { false }

/** Compact player overlays without shrinking their dp geometry or removing system text scaling. */
@Composable
internal fun PlayerOverlayTypography(content: @Composable () -> Unit) {
    if (LocalCompactPlayerText.current || LocalTelevisionChrome.current) {
        content()
        return
    }
    val parent = LocalDensity.current
    val density = remember(parent) { Density(parent.density, parent.fontScale * 0.9f) }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalCompactPlayerText provides true,
        content = content,
    )
}
