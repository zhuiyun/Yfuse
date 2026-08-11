package com.yfuse.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Whether the current Navigation3 entry is the visible route.
 *
 * Navigation3 may retain neighboring entries while it animates or previews a back gesture.
 * Screens use this signal to pause focus requests, carousels, and other visible-only work.
 */
val LocalRouteVisible = staticCompositionLocalOf { true }
