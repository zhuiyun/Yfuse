package com.yfuse.core.designsystem

import androidx.compose.runtime.Composable

/** Android system-back bridge used by the shared Compose navigation shell. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
