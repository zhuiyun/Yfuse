package com.yfuse.core.sync

import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.model.SavedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SyncedUserItem(
    val id: String,
    val title: String,
    val favorite: Boolean,
    val played: Boolean,
    val positionTicks: Long,
    val dateModified: String? = null,
)

@Serializable
enum class SyncMutationKind { Favorite, Played }

@Serializable
data class PendingSyncMutation(
    val serverId: String,
    val itemId: String,
    val title: String,
    val kind: SyncMutationKind,
    val desired: Boolean,
    val baseValue: Boolean?,
    val createdAtEpochMs: Long,
)

data class SyncConflict(
    val mutation: PendingSyncMutation,
    val serverValue: Boolean,
)

data class ServerSyncStatus(
    val serverId: String,
    val serverName: String,
    val syncing: Boolean = false,
    val online: Boolean? = null,
    val lastSyncEpochMs: Long? = null,
    val itemCount: Int = 0,
    val error: String? = null,
)

data class ServerSyncState(
    val statuses: List<ServerSyncStatus> = emptyList(),
    val pendingCount: Int = 0,
    val pendingOperations: List<PendingSyncMutation> = emptyList(),
    val conflicts: List<SyncConflict> = emptyList(),
)

class ServerSyncManager(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val settings: Settings,
) {
    private companion object {
        const val PENDING_KEY = "sync.pending.v1"
        const val AUTO_KEY = "sync.auto"
        const val METADATA_KEY = "sync.metadata"
        const val PROGRESS_KEY = "sync.progress"
        const val ARTWORK_KEY = "sync.artwork"
        const val FAVORITES_KEY = "sync.favorites"
        const val PERIOD_MS = 15 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingSerializer = ListSerializer(PendingSyncMutation.serializer())
    private val pending = MutableStateFlow(loadPending())
    private val snapshots = mutableMapOf<String, List<SyncedUserItem>>()
    private val _state = MutableStateFlow(
        ServerSyncState(
            pendingCount = pending.value.size,
            pendingOperations = pending.value,
        ),
    )
    val state: StateFlow<ServerSyncState> = _state.asStateFlow()
    val autoSync = MutableStateFlow(settings.getBoolean(AUTO_KEY, true))
    val syncMetadata = MutableStateFlow(settings.getBoolean(METADATA_KEY, true))
    val syncProgress = MutableStateFlow(settings.getBoolean(PROGRESS_KEY, true))
    val syncArtwork = MutableStateFlow(settings.getBoolean(ARTWORK_KEY, true))
    val syncFavorites = MutableStateFlow(settings.getBoolean(FAVORITES_KEY, true))
    private var automaticJob: Job? = null
    private var automaticScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (automaticJob != null) return
        automaticScope = scope
        automaticJob = scope.launch {
            while (true) {
                if (autoSync.value && registry.data.value.servers.isNotEmpty()) syncAll()
                delay(PERIOD_MS)
            }
        }
    }

    fun setAutoSync(value: Boolean) {
        autoSync.value = value
        settings.putBoolean(AUTO_KEY, value)
        if (value) automaticScope?.launch { syncAll() }
    }

    fun setMetadata(value: Boolean) {
        syncMetadata.value = value
        settings.putBoolean(METADATA_KEY, value)
    }

    fun setProgress(value: Boolean) {
        syncProgress.value = value
        settings.putBoolean(PROGRESS_KEY, value)
    }

    fun setArtwork(value: Boolean) {
        syncArtwork.value = value
        settings.putBoolean(ARTWORK_KEY, value)
    }

    fun setFavorites(value: Boolean) {
        syncFavorites.value = value
        settings.putBoolean(FAVORITES_KEY, value)
    }

    suspend fun syncCurrent() {
        registry.defaultServer?.let { sync(it) }
    }

    suspend fun syncAll() {
        registry.data.value.servers.forEach { sync(it) }
    }

    suspend fun setFavorite(
        server: SavedServer,
        itemId: String,
        title: String,
        value: Boolean,
    ): Result<Unit> = mutate(server, itemId, title, SyncMutationKind.Favorite, value)

    suspend fun setPlayed(
        server: SavedServer,
        itemId: String,
        title: String,
        value: Boolean,
    ): Result<Unit> = mutate(server, itemId, title, SyncMutationKind.Played, value)

    suspend fun resolveConflict(conflict: SyncConflict, keepLocal: Boolean): Result<Unit> {
        val server = registry.serverById(conflict.mutation.serverId)
            ?: return Result.failure(IllegalStateException("服务器已移除"))
        return if (keepLocal) {
            val mutation = conflict.mutation
            val result = when (mutation.kind) {
                SyncMutationKind.Favorite ->
                    repo.setFavorite(server, mutation.itemId, mutation.desired)
                SyncMutationKind.Played ->
                    repo.setPlayed(server, mutation.itemId, mutation.desired)
            }
            result.onSuccess { removePending(mutation) }
        } else {
            removePending(conflict.mutation)
            Result.success(Unit)
        }
    }

    private suspend fun mutate(
        server: SavedServer,
        itemId: String,
        title: String,
        kind: SyncMutationKind,
        desired: Boolean,
    ): Result<Unit> {
        val base = snapshots[server.id]?.firstOrNull { it.id == itemId }?.let {
            when (kind) {
                SyncMutationKind.Favorite -> it.favorite
                SyncMutationKind.Played -> it.played
            }
        }
        val mutation = PendingSyncMutation(
            serverId = server.id,
            itemId = itemId,
            title = title,
            kind = kind,
            desired = desired,
            baseValue = base,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        addPending(mutation)
        val result = when (kind) {
            SyncMutationKind.Favorite -> repo.setFavorite(server, itemId, desired)
            SyncMutationKind.Played -> repo.setPlayed(server, itemId, desired)
        }
        result.onSuccess { removePending(mutation) }
        return result
    }

    private suspend fun sync(server: SavedServer) {
        setStatus(server) { it.copy(syncing = true, error = null) }
        repo.userLibrarySnapshot(server).fold(
            onSuccess = { remote ->
                val conflicts = detectConflicts(server.id, remote)
                snapshots[server.id] = remote
                setStatus(server) {
                    it.copy(
                        syncing = false,
                        online = true,
                        lastSyncEpochMs = System.currentTimeMillis(),
                        itemCount = remote.size,
                        error = null,
                    )
                }
                _state.value = _state.value.copy(
                    pendingCount = pending.value.size,
                    pendingOperations = pending.value,
                    conflicts = (
                        _state.value.conflicts.filterNot {
                            it.mutation.serverId == server.id
                        } + conflicts
                    ),
                )
                replayNonConflicting(server, conflicts)
            },
            onFailure = { error ->
                setStatus(server) {
                    it.copy(
                        syncing = false,
                        online = false,
                        error = error.message ?: "同步失败",
                    )
                }
            },
        )
    }

    private suspend fun replayNonConflicting(
        server: SavedServer,
        conflicts: List<SyncConflict>,
    ) {
        val blocked = conflicts.map { it.mutation }.toSet()
        pending.value
            .filter { it.serverId == server.id && it !in blocked }
            .forEach { mutation ->
                val result = when (mutation.kind) {
                    SyncMutationKind.Favorite ->
                        repo.setFavorite(server, mutation.itemId, mutation.desired)
                    SyncMutationKind.Played ->
                        repo.setPlayed(server, mutation.itemId, mutation.desired)
                }
                result.onSuccess { removePending(mutation) }
            }
    }

    private fun detectConflicts(
        serverId: String,
        remote: List<SyncedUserItem>,
    ): List<SyncConflict> = pending.value
        .filter { it.serverId == serverId }
        .mapNotNull { mutation ->
            val item = remote.firstOrNull { it.id == mutation.itemId } ?: return@mapNotNull null
            val remoteValue = when (mutation.kind) {
                SyncMutationKind.Favorite -> item.favorite
                SyncMutationKind.Played -> item.played
            }
            if (
                mutation.baseValue != null &&
                remoteValue != mutation.baseValue &&
                remoteValue != mutation.desired
            ) {
                SyncConflict(mutation, remoteValue)
            } else {
                null
            }
        }

    private fun setStatus(
        server: SavedServer,
        transform: (ServerSyncStatus) -> ServerSyncStatus,
    ) {
        val old = _state.value.statuses.firstOrNull { it.serverId == server.id }
            ?: ServerSyncStatus(server.id, server.serverName)
        _state.value = _state.value.copy(
            statuses = _state.value.statuses.filterNot { it.serverId == server.id } +
                transform(old),
            pendingCount = pending.value.size,
            pendingOperations = pending.value,
        )
    }

    private fun addPending(mutation: PendingSyncMutation) {
        commitPending(
            pending.value.filterNot {
                it.serverId == mutation.serverId &&
                    it.itemId == mutation.itemId &&
                    it.kind == mutation.kind
            } + mutation,
        )
    }

    private fun removePending(mutation: PendingSyncMutation) {
        commitPending(
            pending.value.filterNot {
                it.serverId == mutation.serverId &&
                    it.itemId == mutation.itemId &&
                    it.kind == mutation.kind
            },
        )
        _state.value = _state.value.copy(
            pendingCount = pending.value.size,
            pendingOperations = pending.value,
            conflicts = _state.value.conflicts.filterNot { it.mutation == mutation },
        )
    }

    private fun commitPending(value: List<PendingSyncMutation>) {
        pending.value = value
        settings.putString(PENDING_KEY, json.encodeToString(pendingSerializer, value))
        _state.value = _state.value.copy(
            pendingCount = value.size,
            pendingOperations = value,
        )
    }

    private fun loadPending(): List<PendingSyncMutation> =
        settings.getStringOrNull(PENDING_KEY)
            ?.let { runCatching { json.decodeFromString(pendingSerializer, it) }.getOrNull() }
            .orEmpty()
}
