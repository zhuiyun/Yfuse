package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServersData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * Single source of truth for the list of saved servers and the default
 * selection. Backed by multiplatform-settings; exposes a [StateFlow] so all
 * tabs stay in sync.
 */
class ServerRegistry(private val settings: Settings) {

    private companion object {
        const val KEY = "servers.data"
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<ServersData> = _data.asStateFlow()

    val defaultServer: SavedServer? get() = _data.value.defaultServer

    fun serverById(id: String): SavedServer? = _data.value.servers.firstOrNull { it.id == id }

    /** Adds a server (or updates it if the same id already exists). First one becomes default. */
    fun addOrUpdate(server: SavedServer) {
        val current = _data.value
        val servers = current.servers.filterNot { it.id == server.id } + server
        val defaultId = current.defaultServerId ?: server.id
        commit(current.copy(servers = servers, defaultServerId = defaultId))
    }

    fun setDefault(id: String) {
        if (_data.value.servers.any { it.id == id }) {
            commit(_data.value.copy(defaultServerId = id))
        }
    }

    fun remove(id: String) {
        val current = _data.value
        val servers = current.servers.filterNot { it.id == id }
        val defaultId = if (current.defaultServerId == id) servers.firstOrNull()?.id else current.defaultServerId
        commit(current.copy(servers = servers, defaultServerId = defaultId))
    }

    private fun commit(data: ServersData) {
        _data.value = data
        settings.putString(KEY, json.encodeToString(ServersData.serializer(), data))
    }

    private fun load(): ServersData =
        settings.getStringOrNull(KEY)
            ?.let { runCatching { json.decodeFromString(ServersData.serializer(), it) }.getOrNull() }
            ?: ServersData()
}
