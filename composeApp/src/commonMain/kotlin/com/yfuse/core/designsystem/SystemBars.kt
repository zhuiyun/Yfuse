package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Sets the foreground colour of the Android status bar while the caller is composed and its
 * route is visible. Nothing is put back on leaving: the screen that takes over sets its own, and
 * restoring the old value raced a shared-element route's incoming screen, which could be left
 * with dark icons over bright artwork. A screen that changes the icons under something it shows
 * has to set them back itself when that goes.
 */
@Composable
expect fun StatusBarIconStyle(darkIcons: Boolean)
