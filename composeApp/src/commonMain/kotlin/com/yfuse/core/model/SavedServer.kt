package com.yfuse.core.model

import kotlinx.serialization.Serializable

/** A server the user has logged into, with its saved session. */
@Serializable
data class SavedServer(
    val id: String,
    val baseUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
) {
    companion object {
        /** Stable id so re-logging into the same server+user updates one entry. */
        fun idOf(baseUrl: String, userId: String): String = "$baseUrl#$userId"
    }
}

/** The full set of saved servers plus which one is currently the default. */
@Serializable
data class ServersData(
    val servers: List<SavedServer> = emptyList(),
    val defaultServerId: String? = null,
) {
    val defaultServer: SavedServer?
        get() = servers.firstOrNull { it.id == defaultServerId } ?: servers.firstOrNull()
}
