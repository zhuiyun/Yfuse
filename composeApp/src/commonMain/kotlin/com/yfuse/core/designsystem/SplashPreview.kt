package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A looping thumbnail of one launch choreography, so the two can be compared in settings
 * without relaunching the app. Draws the mark only — no background, wordmark or tagline.
 */
@Composable
expect fun SplashPreview(
    variant: SplashAnimation,
    playing: Boolean,
    modifier: Modifier,
)
