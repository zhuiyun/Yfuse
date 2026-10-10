package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Plays the same choreography as launch once, then holds the resolved logo. The caller can
 * recreate this with a replay key. Reduced motion keeps the selected logo without animation.
 */
@Composable
expect fun SplashPreview(
    variant: SplashAnimation,
    playing: Boolean,
    modifier: Modifier,
)
