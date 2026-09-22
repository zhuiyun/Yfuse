package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Controls the foreground colour of the Android status bar while the caller is
 * composed. The previous style is restored when leaving the screen.
 */
@Composable
expect fun StatusBarIconStyle(darkIcons: Boolean)
