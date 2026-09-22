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
    val permission = localNetworkRuntimePermission(Build.VERSION.SDK_INT) ?: return true
    val context = androidAppContext ?: return true
    return ContextCompat.checkSelfPermission(
        context,
        permission,
    ) == PackageManager.PERMISSION_GRANTED
}

/** Android 16 retains its opt-in bridge; Android 17 gates every LAN connection. */
fun localNetworkRuntimePermission(sdk: Int = Build.VERSION.SDK_INT): String? =
    when {
        sdk >= 37 -> Manifest.permission.ACCESS_LOCAL_NETWORK
        sdk == 36 -> Manifest.permission.NEARBY_WIFI_DEVICES
        else -> null
    }
