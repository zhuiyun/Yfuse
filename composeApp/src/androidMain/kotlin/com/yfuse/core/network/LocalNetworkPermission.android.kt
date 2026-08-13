package com.yfuse.core.network

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yfuse.core.util.androidAppContext

internal fun requireLocalNetworkPermission() {
    if (!localNetworkPermissionGranted()) {
        throw LocalNetworkPermissionRequiredException()
    }
}

actual fun localNetworkPermissionGranted(): Boolean {
    // NEARBY_WIFI_DEVICES is only the Android 16 opt-in bridge for local-network
    // protection. Requesting it on Android 13-15 would show an unrelated prompt even
    // though those releases do not gate LAN sockets this way.
    if (Build.VERSION.SDK_INT < 36) return true
    val context = androidAppContext ?: return true
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.NEARBY_WIFI_DEVICES,
    ) == PackageManager.PERMISSION_GRANTED
}
