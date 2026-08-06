package com.yfuse.core.sync

import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
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

        /** Ceiling on the exponential hold-off for a server that keeps failing. */
        const val MAX_BACKOFF_MS = 60 * 60 * 1000L

        /** A rejected credential waits for the user, not for the clock. */
        const val UNAUTHORIZED_BACKOFF_MS = 6 * 60 * 60 * 1000L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pendingSerializer = ListSerializer(PendingSyncMutation.serializer())
    private val pending = MutableStateFlow(loadPending())
    private val snapshots = mutableMapOf<String, List<SyncedUserItem>>()

    /**
     * Per-server consecutive failures, and the earliest time each may be tried again.
     *
     * Every server in the list used to be retried on every cycle regardless of history. In
     * practice one server timed out on all fourteen of its attempts across a week of logs and
     * another answered 403 six times running, and each of those cost the sweep a full request
     * budget before the servers that do work were reached.
     */
    private val failureStreaks = mutableMapOf<String, Int>()
    private val retryNotBeforeEpochMs = mutableMapOf<String, Long>()
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
        AppLog.info(
            category = "sync",
            event = "automatic_sync_started",
            message = "Automatic server synchronization started",
            attributes = mapOf(
                "enabled" to autoSync.value.toString(),
                "pendingCount" to pending.value.size.toString(),
            ),
        )
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

    /**
     * [force] bypasses the per-server hold-off, for a sync the user asked for by name.
     * Without it 立即同步 would silently do nothing for exactly the servers the user is
     * pressing it because of.
     */
    suspend fun syncAll(force: Boolean = false) {
        registry.data.value.servers.forEach { sync(it, force) }
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
            result
                .onSuccess {
                    removePending(mutation)
                    AppLog.info(
                        category = "sync",
                        event = "conflict_resolved",
                        message = "Synchronization conflict resolved with local value",
                        attributes = mapOf("kind" to mutation.kind.name),
                    )
                }
                .onFailure {
                    AppLog.warning(
                        category = "sync",
                        event = "conflict_resolution_failed",
                        message = "Failed to resolve synchronization conflict",
                        throwable = it,
                        attributes = mapOf("kind" to mutation.kind.name),
                    )
                }
        } else {
            removePending(conflict.mutation)
            AppLog.info(
                category = "sync",
                event = "conflict_resolved",
                message = "Synchronization conflict resolved with server value",
                attributes = mapOf("kind" to conflict.mutation.kind.name),
            )
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
        AppLog.info(
            category = "sync",
            event = "mutation_queued",
            message = "User state mutation queued for synchronization",
            attributes = mapOf(
                "serverId" to server.id,
                "kind" to kind.name,
                "desired" to desired.toString(),
            ),
        )
        val result = when (kind) {
            SyncMutationKind.Favorite -> repo.setFavorite(server, itemId, desired)
            SyncMutationKind.Played -> repo.setPlayed(server, itemId, desired)
        }
        result
            .onSuccess { removePending(mutation) }
            .onFailure {
                AppLog.warning(
                    category = "sync",
                    event = "mutation_deferred",
                    message = "User state mutation remains queued after request failure",
                    throwable = it,
                    attributes = mapOf(
                        "serverId" to server.id,
                        "kind" to kind.name,
                    ),
                )
            }
        return result
    }

    private suspend fun sync(server: SavedServer, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val notBefore = retryNotBeforeEpochMs[server.id]?.takeUnless { force }
        if (notBefore != null && now < notBefore) {
            AppLog.info(
                category = "sync",
                event = "server_sync_skipped",
                message = "Server synchronization skipped while backing off",
                attributes = mapOf(
                    "serverId" to server.id,
                    "failureStreak" to (failureStreaks[server.id] ?: 0).toString(),
                    "retryInMs" to (notBefore - now).toString(),
                ),
            )
            return
        }
        setStatus(server) { it.copy(syncing = true, error = null) }
        repo.userLibrarySnapshot(server).fold(
            onSuccess = { remote ->
                failureStreaks.remove(server.id)
                retryNotBeforeEpochMs.remove(server.id)
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
                if (conflicts.isNotEmpty()) {
                    AppLog.warning(
                        category = "sync",
                        event = "conflicts_detected",
                        message = "Synchronization conflicts detected",
                        attributes = mapOf(
                            "serverId" to server.id,
                            "conflictCount" to conflicts.size.toString(),
                        ),
                    )
                }
                AppLog.info(
                    category = "sync",
                    event = "server_sync_completed",
                    message = "Server synchronization completed",
                    attributes = mapOf(
                        "serverId" to server.id,
                        "itemCount" to remote.size.toString(),
                        "conflictCount" to conflicts.size.toString(),
                        "pendingCount" to pending.value.size.toString(),
                    ),
                )
                replayNonConflicting(server, conflicts)
            },
            onFailure = { error ->
                val unauthorized = error.isUnauthorized()
                val streak = (failureStreaks[server.id] ?: 0) + 1
                failureStreaks[server.id] = streak
                val backoffMs = backoffFor(streak, unauthorized)
                retryNotBeforeEpochMs[server.id] = System.currentTimeMillis() + backoffMs
                AppLog.warning(
                    category = "sync",
                    event = "server_sync_failed",
                    message = "Server synchronization failed",
                    throwable = error,
                    attributes = mapOf(
                        "serverId" to server.id,
                        "failureStreak" to streak.toString(),
                        "backoffMs" to backoffMs.toString(),
                        "unauthorized" to unauthorized.toString(),
                    ),
                )
                setStatus(server) {
                    it.copy(
                        syncing = false,
                        online = false,
                        error = when {
                            // A revoked token is not a network hiccup, and the old wording
                            // sent the user looking at their connection instead of re-signing in.
                            unauthorized -> "登录已失效，请重新登录该服务器"
                            else -> error.message ?: "同步失败"
                        },
                    )
                }
            },
        )
    }

    /**
     * How long to leave a failing server alone.
     *
     * An expired credential is held off far longer than a timeout: no number of retries fixes
     * it, and the user has been told what to do about it. Both are capped so that a server
     * which comes back is picked up again within an hour without the app being restarted.
     */
    private fun backoffFor(streak: Int, unauthorized: Boolean): Long {
        if (unauthorized) return UNAUTHORIZED_BACKOFF_MS
        val exponent = (streak - 1).coerceIn(0, 5)
        return (PERIOD_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun Throwable.isUnauthorized(): Boolean =
        (this as? EmbyErrorException)?.error == EmbyError.Unauthorized

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
                result
                    .onSuccess { removePending(mutation) }
                    .onFailure {
                        AppLog.warning(
                            category = "sync",
                            event = "pending_replay_failed",
                            message = "Queued synchronization mutation replay failed",
                            throwable = it,
                            attributes = mapOf(
                                "serverId" to server.id,
                                "kind" to mutation.kind.name,
                            ),
                        )
                    }
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
        runCatching {
            settings.putString(PENDING_KEY, json.encodeToString(pendingSerializer, value))
        }.onFailure {
            AppLog.error(
                category = "sync",
                event = "pending_persist_failed",
                message = "Failed to persist queued synchronization mutations",
                throwable = it,
                attributes = mapOf("pendingCount" to value.size.toString()),
            )
        }
        _state.value = _state.value.copy(
            pendingCount = value.size,
            pendingOperations = value,
        )
    }

    private fun loadPending(): List<PendingSyncMutation> {
        val raw = settings.getStringOrNull(PENDING_KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(pendingSerializer, raw)
        }.onFailure {
            AppLog.error(
                category = "sync",
                event = "stored_pending_invalid",
                message = "Stored synchronization queue could not be decoded",
                throwable = it,
            )
        }.getOrDefault(emptyList())
    }
}
