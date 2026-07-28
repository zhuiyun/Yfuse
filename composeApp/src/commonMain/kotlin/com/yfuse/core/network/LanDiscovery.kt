package com.yfuse.core.network

data class DiscoveredServer(
    val name: String,
    val address: String,
    val id: String,
)

interface LanDiscovery {
    suspend fun discover(timeoutMs: Long = 2_500L): List<DiscoveredServer>
}

expect fun createLanDiscovery(): LanDiscovery
