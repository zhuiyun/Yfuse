package com.yfuse.core.network

import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Requests local-network access for user-initiated connections, discovery and casting.
 * The caller decides whether a denied request can continue using an Internet endpoint.
 */
@Composable
@SuppressLint("InlinedApi")
actual fun rememberLocalNetworkPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (localNetworkConnectionsRestricted()) {
                // Do not immediately show the upgrade notice after a manual connection was declined.
                context
                    .getSharedPreferences("local_network_access", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("explained", !granted)
                    .apply()
            }
            if (granted) onGranted() else onDenied()
        }
    return remember(launcher, onGranted, onDenied) {
        {
            if (localNetworkPermissionGranted()) {
                onGranted()
            } else {
                localNetworkRuntimePermission()?.let { launcher.launch(it) }
            }
        }
    }
}
