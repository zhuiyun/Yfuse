package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/**
 * Lets touches through the dialog window this is composed in, while [enabled].
 *
 * A dialog playing its exit is already gone as far as the person is concerned, but its window
 * still covered the screen and took the tap meant for the list underneath — a close followed
 * quickly by a tap lost the tap. Call it inside the dialog's content.
 */
@Composable
internal expect fun DialogTouchPassThrough(enabled: Boolean)

internal data class DialogSystemBarStyle(
    val statusBarDarkIcons: Boolean,
    val navigationBarDarkIcons: Boolean,
)

/** Capture the owning window before entering the new dialog's composition. */
@Composable
internal expect fun rememberDialogSystemBarStyle(fallbackDarkIcons: Boolean): DialogSystemBarStyle

/** The full-window Compose scrim owns dimming; Android system bars remain transparent over it. */
@Composable
internal expect fun DialogSystemBars(style: DialogSystemBarStyle)
