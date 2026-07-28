package com.yfuse.core.cast

import kotlinx.coroutines.flow.StateFlow

data class CastDevice(val id: String, val name: String)

data class CastState(
    val devices: List<CastDevice> = emptyList(),
    val discovering: Boolean = false,
    val activeDeviceId: String? = null,
    val error: String? = null,
)

interface CastManager {
    val state: StateFlow<CastState>
    suspend fun discover()
    suspend fun play(deviceId: String, mediaUrl: String, title: String)
    suspend fun stop()
}

expect fun createCastManager(): CastManager
