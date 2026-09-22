package com.yfuse.core.network

import androidx.compose.runtime.Composable

/** Returns a callback that continues only after local-network access is available. */
@Composable
expect fun rememberLocalNetworkPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit = {},
): () -> Unit
