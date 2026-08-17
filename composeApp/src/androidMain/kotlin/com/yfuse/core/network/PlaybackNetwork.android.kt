package com.yfuse.core.network

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yfuse.core.data.PlaybackNetworkClass
import com.yfuse.core.util.androidAppContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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

/** Emits default-network changes for the player recovery state machine. */
fun playbackNetworkClasses(): Flow<PlaybackNetworkClass> =
    callbackFlow {
        val context = androidAppContext
        val connectivity = context?.getSystemService(ConnectivityManager::class.java)
        if (connectivity == null) {
            trySend(PlaybackNetworkClass.Unknown)
            close()
            return@callbackFlow
        }

        fun publish() {
            trySend(currentPlaybackNetworkClass())
        }

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = publish()

                override fun onLost(network: android.net.Network) = publish()

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: NetworkCapabilities,
                ) = publish()

                override fun onUnavailable() = publish()
            }
        publish()
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure {
                close(it)
                return@callbackFlow
            }
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
