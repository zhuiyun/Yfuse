package com.yfuse.core.network

import androidx.compose.runtime.Composable

/** Returns a callback that continues only after local-network access is available. */
@Composable
expect fun rememberLocalNetworkPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit = {},
): () -> Unit

/** Whether this platform also protects direct LAN connections, rather than discovery alone. */
expect fun localNetworkConnectionsRestricted(): Boolean

/** Explain the new permission to users upgrading with saved server connections. */
@Composable
expect fun LocalNetworkAccessNotice(
    hasServers: Boolean,
    onGranted: () -> Unit,
)
