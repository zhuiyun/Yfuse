package com.yfuse.core.data

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.model.ServersData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
private data class PortableServerBackup(
    @SerialName("v") val version: Int = 1,
    @SerialName("d") val defaultServerId: String? = null,
    @SerialName("s") val servers: List<PortableServer> = emptyList(),
)

@Serializable
private data class PortableServer(
    @SerialName("b") val baseUrl: String,
    @SerialName("n") val serverName: String,
    @SerialName("u") val userId: String,
    @SerialName("a") val userName: String,
    @SerialName("t") val accessToken: String,
)

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
        val replacing = current.servers.any { it.id == server.id }
        val servers = current.servers.filterNot { it.id == server.id } + server
        val defaultId = current.defaultServerId ?: server.id
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = if (replacing) "server_updated" else "server_added",
            message = "Saved server registry changed",
            attributes = mapOf(
                "serverId" to server.id,
                "serverCount" to servers.size.toString(),
            ),
        )
    }

    fun setDefault(id: String) {
        if (_data.value.servers.any { it.id == id }) {
            commit(_data.value.copy(defaultServerId = id))
            AppLog.info(
                category = "server.registry",
                event = "default_changed",
                message = "Default server changed",
                attributes = mapOf("serverId" to id),
            )
        } else {
            AppLog.warning(
                category = "server.registry",
                event = "default_missing",
                message = "Ignored request to select an unknown server",
            )
        }
    }

    fun remove(id: String) {
        val current = _data.value
        val servers = current.servers.filterNot { it.id == id }
        val defaultId = if (current.defaultServerId == id) servers.firstOrNull()?.id else current.defaultServerId
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = "server_removed",
            message = "Saved server removed",
            attributes = mapOf(
                "serverId" to id,
                "serverCount" to servers.size.toString(),
            ),
        )
    }

    /** Versioned portable backup shared by file and QR import/export. */
    fun exportBackup(): String {
        val current = _data.value
        AppLog.info(
            category = "server.migration",
            event = "backup_created",
            message = "Portable server backup created",
            attributes = mapOf("serverCount" to current.servers.size.toString()),
        )
        return json.encodeToString(
            PortableServerBackup.serializer(),
            PortableServerBackup(
                defaultServerId = current.defaultServerId,
                servers = current.servers.map {
                    PortableServer(it.baseUrl, it.serverName, it.userId, it.userName, it.accessToken)
                },
            ),
        )
    }

    /**
     * Validates then merges a portable backup. Existing server/user pairs are
     * updated with the imported token; unrelated local entries are preserved.
     */
    fun importBackup(payload: String): Result<Int> =
        runCatching {
            val backup = json.decodeFromString(PortableServerBackup.serializer(), payload.trim())
            require(backup.version == 1) { "不支持的备份版本" }
            require(backup.servers.isNotEmpty()) { "备份中没有服务器" }
            val imported = backup.servers.map {
                require(it.baseUrl.startsWith("http://") || it.baseUrl.startsWith("https://")) {
                    "服务器地址无效"
                }
                require(it.userId.isNotBlank() && it.accessToken.isNotBlank()) { "账号凭据不完整" }
                SavedServer(
                    id = SavedServer.idOf(it.baseUrl, it.userId),
                    baseUrl = it.baseUrl,
                    serverName = it.serverName,
                    userId = it.userId,
                    userName = it.userName,
                    accessToken = it.accessToken,
                )
            }
            val current = _data.value
            val ids = imported.mapTo(hashSetOf()) { it.id }
            val merged = current.servers.filterNot { it.id in ids } + imported
            val importedDefault = backup.defaultServerId?.let { oldId ->
                backup.servers.firstOrNull {
                    SavedServer.idOf(it.baseUrl, it.userId) == oldId
                }?.let { SavedServer.idOf(it.baseUrl, it.userId) }
            }
            commit(
                ServersData(
                    servers = merged,
                    defaultServerId = current.defaultServerId ?: importedDefault ?: imported.first().id,
                ),
            )
            imported.size
        }
            .onSuccess {
                AppLog.info(
                    category = "server.migration",
                    event = "backup_imported",
                    message = "Portable server backup imported",
                    attributes = mapOf("serverCount" to it.toString()),
                )
            }
            .onFailure {
                AppLog.warning(
                    category = "server.migration",
                    event = "backup_import_failed",
                    message = "Portable server backup import failed",
                    throwable = it,
                )
            }

    private fun commit(data: ServersData) {
        _data.value = data
        settings.putString(KEY, json.encodeToString(ServersData.serializer(), data))
    }

    private fun load(): ServersData {
        val raw = settings.getStringOrNull(KEY) ?: return ServersData()
        return runCatching {
            json.decodeFromString(ServersData.serializer(), raw)
        }.onFailure {
            AppLog.error(
                category = "server.registry",
                event = "stored_data_invalid",
                message = "Saved server registry could not be decoded",
                throwable = it,
            )
        }.getOrDefault(ServersData())
    }
}
