package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/** Commit-only back interception for modals and non-animated navigation decisions. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * System predictive-back progress for an in-app route that has a real previous route.
 *
 * Android feeds this from androidx.activity.compose.PredictiveBackHandler. Common UI code
 * receives only normalized progress and swipe edge; it does not invent gesture timing.
 */
@Composable
expect fun PlatformBackGestureHandler(
    enabled: Boolean = true,
    onProgress: (Float, BackGestureEdge) -> Unit,
    onCancel: () -> Unit,
    onBack: () -> Unit,
)
