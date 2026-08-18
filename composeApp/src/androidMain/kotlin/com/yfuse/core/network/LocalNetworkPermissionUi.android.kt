package com.yfuse.core.network

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Requests Android's local-network permission only when the user starts discovery/casting.
 * A denial intentionally does not block manual server entry or regular Internet playback.
 */
@Composable
@SuppressLint("InlinedApi")
actual fun rememberLocalNetworkPermissionRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): () -> Unit {
    val launcher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) onGranted() else onDenied()
        }
    return remember(launcher, onGranted, onDenied) {
        {
            if (localNetworkPermissionGranted()) {
                onGranted()
            } else {
                launcher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
    }
}
