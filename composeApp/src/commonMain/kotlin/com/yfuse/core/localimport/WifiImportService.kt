package com.yfuse.core.localimport

import kotlinx.coroutines.flow.StateFlow

data class WifiImportedFile(
    val id: String,
    val name: String,
    val uri: String,
    val sizeBytes: Long,
)

data class WifiImportState(
    val running: Boolean = false,
    val address: String? = null,
    val pairingCode: String? = null,
    val receiving: Boolean = false,
    val files: List<WifiImportedFile> = emptyList(),
    val message: String? = null,
)

interface WifiImportService {
    val state: StateFlow<WifiImportState>

    suspend fun load()

    suspend fun start()

    fun stop()

    suspend fun remove(id: String)
}

expect fun createWifiImportService(): WifiImportService
