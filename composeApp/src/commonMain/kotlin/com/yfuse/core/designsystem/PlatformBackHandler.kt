package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/** Commit-only bridge for the shell-level "non-Home root returns Home" decision. */
@Composable
expect fun PlatformBackHandler(
    enabled: Boolean = true,
    onBack: () -> Unit,
)
