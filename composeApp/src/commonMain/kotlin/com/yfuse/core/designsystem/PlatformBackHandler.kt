package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEvent

/** Commit-only bridge for the shell-level "non-Home root returns Home" decision. */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)

/** Progress-aware bridge for overlays that can follow the Android predictive-back gesture. */
@Composable
expect fun PlatformPredictiveBackHandler(
    enabled: Boolean = true,
    onProgress: (Float) -> Unit,
    onBack: () -> Unit,
    onCancel: () -> Unit,
)

/** Registers with the system back dispatcher, preserving the edge and the full gesture lifecycle. */
@Composable
expect fun PlatformNavigationBackHandler(
    enabled: Boolean,
    onStarted: (NavigationEvent) -> Unit,
    onProgressed: (NavigationEvent) -> Unit,
    onCompleted: () -> Unit,
    onCancelled: () -> Unit,
)
