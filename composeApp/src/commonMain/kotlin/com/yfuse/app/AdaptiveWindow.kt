package com.yfuse.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.yfuse.app.RootComponent.Tab

internal data class TabItem(
    val tab: Tab,
    val label: String,
    val icon: ImageVector,
)

internal val LocalWideWindow = staticCompositionLocalOf { false }
internal val LocalFloatingDockVisible = staticCompositionLocalOf { false }
