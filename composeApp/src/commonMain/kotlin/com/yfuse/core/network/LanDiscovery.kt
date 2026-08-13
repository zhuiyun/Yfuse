package com.yfuse.core.network

data class DiscoveredServer(
    val name: String,
    val address: String,
    val id: String,
    val version: String? = null,
)

interface LanDiscovery {
    suspend fun discover(timeoutMs: Long = 2_500L): List<DiscoveredServer>
}

class LocalNetworkPermissionRequiredException :
    SecurityException(
        "需要“附近的设备”权限才能发现局域网服务器或投屏设备",
    )

/** Platform permission needed before LAN discovery can open broadcast sockets. */
expect fun localNetworkPermissionGranted(): Boolean

expect fun createLanDiscovery(): LanDiscovery
