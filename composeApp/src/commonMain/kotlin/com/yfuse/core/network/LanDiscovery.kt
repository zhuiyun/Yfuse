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
        "需要允许“附近的设备”访问才能连接局域网服务器或投屏；可在设置的权限检查中开启",
    )

/** Platform permission needed before LAN discovery can open broadcast sockets. */
expect fun localNetworkPermissionGranted(): Boolean

expect fun createLanDiscovery(): LanDiscovery
