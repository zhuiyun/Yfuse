package com.yfuse.core.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.util.androidAppContext

actual fun currentPlaybackNetworkClass(): PlaybackNetworkClass {
    val context = androidAppContext ?: return PlaybackNetworkClass.Unknown
    val connectivity =
        context.getSystemService(ConnectivityManager::class.java)
            ?: return PlaybackNetworkClass.Unknown
    val network = connectivity.activeNetwork ?: return PlaybackNetworkClass.Offline
    val capabilities =
        connectivity.getNetworkCapabilities(network)
            ?: return PlaybackNetworkClass.Offline
    if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
        return PlaybackNetworkClass.Offline
    }
    return if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
        PlaybackNetworkClass.Unmetered
    } else {
        PlaybackNetworkClass.Metered
    }
}
