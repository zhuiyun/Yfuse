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

/**
 * Enough history for stale routes and queued work without letting repeated address edits grow
 * the persisted registry forever. Iteration order is oldest to newest among the retained ids.
 */
internal const val MAX_SERVER_PREVIOUS_IDS = 8

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

    fun serverById(id: String): SavedServer? =
        _data.value.servers.firstOrNull { it.id == id }
            ?: _data.value.servers.firstOrNull { id in it.previousIds }

    /** Adds a server (or updates it if the same id already exists). First one becomes default. */
    fun addOrUpdate(server: SavedServer) {
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == server.id }
        val replacing = existing != null
        val normalized = server.copy(
            previousIds = recentPreviousIds(
                server.id,
                server.previousIds,
                existing?.previousIds.orEmpty(),
            ),
        )
        val servers = current.servers
            .filterNot { it.id == server.id }
            .map { it.copy(previousIds = it.previousIds - server.id) } + normalized
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

    /** Updates the user-visible server name without touching its login session or identity. */
    fun rename(id: String, name: String): Boolean {
        val normalized = name
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .take(60)
        if (normalized.isBlank()) return false
        val current = _data.value
        val existing = current.servers.firstOrNull { it.id == id } ?: return false
        if (existing.serverName == normalized) return true
        commit(
            current.copy(
                servers = current.servers.map {
                    if (it.id == id) it.copy(serverName = normalized) else it
                },
            ),
        )
        AppLog.info(
            category = "server.registry",
            event = "server_renamed",
            message = "Saved server display name changed",
            attributes = mapOf("serverId" to id),
        )
        return true
    }

    /** Atomically replaces an edited server while preserving its list position and default. */
    fun replace(id: String, server: SavedServer): Boolean {
        val current = _data.value
        val oldIndex = current.servers.indexOfFirst { it.id == id }
        if (oldIndex < 0) return false
        val existing = current.servers[oldIndex]
        val colliding = current.servers.firstOrNull { it.id == server.id }
        val replacement = server.copy(
            previousIds = recentPreviousIds(
                server.id,
                server.previousIds,
                existing.previousIds,
                colliding?.previousIds.orEmpty(),
                listOf(id),
            ),
        )
        val remaining = current.servers
            .filterNot { it.id == id || it.id == server.id }
            .map { it.copy(previousIds = it.previousIds - server.id) }
        val servers = remaining.toMutableList().apply {
            add(oldIndex.coerceAtMost(size), replacement)
        }
        val defaultId = when (current.defaultServerId) {
            id, server.id -> server.id
            else -> current.defaultServerId
        }
        commit(current.copy(servers = servers, defaultServerId = defaultId))
        AppLog.info(
            category = "server.registry",
            event = "server_replaced",
            message = "Saved server connection changed",
            attributes = mapOf(
                "previousServerId" to id,
                "serverId" to server.id,
            ),
        )
        return true
    }

    fun remove(id: String) {
        val current = _data.value
        val servers = current.servers
            .filterNot { it.id == id }
            .map { it.copy(previousIds = it.previousIds - id) }
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

    /**
     * Replaces the local registry with a decrypted account-sync snapshot.
     *
     * The cloud service only stores ciphertext, so validation belongs here after authenticated
     * decryption. IDs are re-derived instead of trusted and the whole change is committed once,
     * preventing observers from seeing a half-applied server list.
     */
    fun replaceFromSync(snapshot: ServersData): Result<Int> = runCatching {
        require(snapshot.servers.size <= 100) { "同步的服务器数量过多" }
        val normalized = snapshot.servers.map { server ->
            val baseUrl = server.baseUrl.trim().trimEnd('/')
            require(baseUrl.length in 8..2_048 &&
                (baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))
            ) { "同步的服务器地址无效" }
            val userId = server.userId.trim()
            val token = server.accessToken.trim()
            require(userId.isNotEmpty() && userId.length <= 256 && token.length in 1..4_096) {
                "同步的服务器凭据无效"
            }
            server.copy(
                id = SavedServer.idOf(baseUrl, userId),
                baseUrl = baseUrl,
                serverName = server.serverName
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
                    .take(60)
                    .ifBlank { "Emby" },
                userId = userId,
                userName = server.userName
                    .replace('\r', ' ')
                    .replace('\n', ' ')
                    .trim()
                    .take(128),
                accessToken = token,
                previousIds = recentPreviousIds(
                    SavedServer.idOf(baseUrl, userId),
                    server.previousIds.take(MAX_SERVER_PREVIOUS_IDS),
                ),
            )
        }
        require(normalized.map { it.id }.distinct().size == normalized.size) {
            "同步数据中包含重复服务器"
        }
        val requestedDefault = snapshot.defaultServerId
        val defaultId = requestedDefault?.let { oldId ->
            snapshot.servers.indexOfFirst { it.id == oldId }
                .takeIf { it >= 0 }
                ?.let(normalized::get)
                ?.id
        } ?: normalized.firstOrNull()?.id
        commit(ServersData(normalized, defaultId))
        normalized.size
    }

    /** Versioned portable backup shared by file and QR import/export, including display names. */
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
            val backup = try {
                json.decodeFromString(PortableServerBackup.serializer(), payload.trim())
            } catch (e: Exception) {
                error("二维码内容无法识别，请确认扫描的是 Yfuse 服务器迁移码")
            }
            require(backup.version == 1) { "不支持的备份版本" }
            require(backup.servers.isNotEmpty()) { "备份中没有服务器" }
            val current = _data.value
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
                    previousIds = current.servers
                        .firstOrNull { saved ->
                            saved.id == SavedServer.idOf(it.baseUrl, it.userId)
                        }
                        ?.previousIds
                        .orEmpty(),
                )
            }
            val ids = imported.mapTo(hashSetOf()) { it.id }
            val merged = current.servers
                .filterNot { it.id in ids }
                .map { it.copy(previousIds = it.previousIds - ids) } + imported
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
        val normalized = data.withBoundedPreviousIds()
        _data.value = normalized
        settings.putString(KEY, json.encodeToString(ServersData.serializer(), normalized))
        clearOrphanedLibraryCaches(normalized)
    }

    private fun load(): ServersData {
        val raw = settings.getStringOrNull(KEY) ?: return ServersData()
        return runCatching {
            val decoded = json.decodeFromString(ServersData.serializer(), raw)
            val normalized = decoded.withBoundedPreviousIds()
            if (normalized != decoded) {
                // One-time migration for registries written before alias history was bounded.
                // A failed cleanup must not make otherwise valid saved servers disappear.
                runCatching {
                    settings.putString(KEY, json.encodeToString(ServersData.serializer(), normalized))
                }.onFailure { error ->
                    AppLog.warning(
                        category = "server.registry",
                        event = "alias_history_migration_failed",
                        message = "Saved server alias history could not be compacted",
                        throwable = error,
                    )
                }
            }
            clearOrphanedLibraryCaches(normalized)
            normalized
        }.onFailure {
            AppLog.error(
                category = "server.registry",
                event = "stored_data_invalid",
                message = "Saved server registry could not be decoded",
                throwable = it,
            )
        }.getOrDefault(ServersData())
    }

    private fun clearOrphanedLibraryCaches(data: ServersData) {
        runCatching {
            LibraryCache(settings).clearOrphans(data.servers.mapTo(mutableSetOf()) { it.id })
        }.onFailure { error ->
            // Registry state has already loaded/committed. Cache cleanup is best-effort and
            // must never make a valid connection edit appear to have failed.
            AppLog.warning(
                category = "server.registry",
                event = "orphan_library_cache_clear_failed",
                message = "Orphaned library caches could not be cleared",
                throwable = error,
            )
        }
    }
}

/**
 * Later occurrences win so reusing an older alias makes it recent again. This matters when a
 * replacement collides with an existing saved connection and both histories are merged.
 */
private fun recentPreviousIds(
    currentId: String,
    vararg histories: Iterable<String>,
): Set<String> {
    val newestFirst = buildList {
        histories.forEach { addAll(it) }
    }.asReversed()
        .asSequence()
        .filter { it != currentId }
        .distinct()
        .take(MAX_SERVER_PREVIOUS_IDS)
        .toList()

    return linkedSetOf<String>().apply {
        newestFirst.asReversed().forEach { add(it) }
    }
}

private fun ServersData.withBoundedPreviousIds(): ServersData = copy(
    servers = servers.map { server ->
        server.copy(previousIds = recentPreviousIds(server.id, server.previousIds))
    },
)
