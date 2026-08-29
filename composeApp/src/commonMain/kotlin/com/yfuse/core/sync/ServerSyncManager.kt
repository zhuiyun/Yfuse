package com.yfuse.core.sync

import com.russhwolf.settings.Settings
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.model.SavedServer
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.network.knownUnavailableEndpointReason
import com.yfuse.core.network.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

private const val MAX_PENDING_MUTATIONS = 512
private const val MAX_PENDING_SERIALIZED_BYTES = 512 * 1024
private const val MAX_PENDING_SERVER_ID_BYTES = 128
private const val MAX_PENDING_ITEM_ID_BYTES = 512
private const val MAX_PENDING_TITLE_CHARS = 256
private val boundedPendingJson = Json { encodeDefaults = true }

/** Keeps the newest distinct operations that fit both the entry and persisted-byte budgets. */
internal fun boundPendingMutations(
    value: List<PendingSyncMutation>,
    maxEntries: Int = MAX_PENDING_MUTATIONS,
    maxSerializedBytes: Int = MAX_PENDING_SERIALIZED_BYTES,
): List<PendingSyncMutation> {
    require(maxEntries > 0) { "maxEntries must be positive" }
    require(maxSerializedBytes > 2) { "maxSerializedBytes must fit a JSON array" }
    val seen = hashSetOf<Triple<String, String, SyncMutationKind>>()
    val newestFirst = ArrayList<PendingSyncMutation>(minOf(value.size, maxEntries))
    var serializedBytes = 2 // []
    value.asReversed().forEach { mutation ->
        val normalized = mutation.normalizedForPendingStorage() ?: return@forEach
        val key = Triple(normalized.serverId, normalized.itemId, normalized.kind)
        if (!seen.add(key) || newestFirst.size >= maxEntries) return@forEach
        val entryBytes =
            boundedPendingJson
                .encodeToString(PendingSyncMutation.serializer(), normalized)
                .encodeToByteArray()
                .size + if (newestFirst.isEmpty()) 0 else 1
        if (serializedBytes + entryBytes > maxSerializedBytes) return@forEach
        newestFirst += normalized
        serializedBytes += entryBytes
    }
    return newestFirst.asReversed()
}

private fun PendingSyncMutation.normalizedForPendingStorage(): PendingSyncMutation? {
    if (serverId.isBlank() || serverId.encodeToByteArray().size > MAX_PENDING_SERVER_ID_BYTES) {
        return null
    }
    if (itemId.isBlank() || itemId.encodeToByteArray().size > MAX_PENDING_ITEM_ID_BYTES) {
        return null
    }
    return copy(title = title.take(MAX_PENDING_TITLE_CHARS))
}

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

@Serializable
private data class ServerRetryState(
    val serverId: String,
    val failureStreak: Int,
    val retryNotBeforeEpochMs: Long,
)

class ServerSyncManager(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val settings: Settings,
    private val progressPreferences: ProgressSyncPreferences = ProgressSyncPreferences(settings),
) {
    private companion object {
        const val PENDING_KEY = "sync.pending.v1"
        const val AUTO_KEY = "sync.auto"
        const val METADATA_KEY = "sync.metadata"
        const val ARTWORK_KEY = "sync.artwork"
        const val FAVORITES_KEY = "sync.favorites"
        const val RETRY_STATE_KEY = "sync.retry.v1"
        const val PERIOD_MS = 15 * 60 * 1000L

        /** Once the exponent reaches this streak, additional failures use the same ceiling. */
        const val MAX_FAILURE_STREAK = 6

        /** Ceiling on the exponential hold-off for a server that keeps failing. */
        const val MAX_BACKOFF_MS = 60 * 60 * 1000L

        /** A rejected credential waits for the user, not for the clock. */
        const val UNAUTHORIZED_BACKOFF_MS = 6 * 60 * 60 * 1000L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val pendingSerializer = ListSerializer(PendingSyncMutation.serializer())
    private val retryStateSerializer = ListSerializer(ServerRetryState.serializer())
    private val pending = MutableStateFlow(loadPending())
    private val snapshots = mutableMapOf<String, List<SyncedUserItem>>()
    private val syncMutex = Mutex()

    /**
     * Persisted per-server consecutive failures, and the earliest time each may be tried again.
     *
     * Every server in the list used to be retried on every cycle regardless of history. In
     * practice one server timed out on all fourteen of its attempts across a week of logs and
     * another answered 403 six times running, and each of those cost the sweep a full request
     * budget before the servers that do work were reached.
     */
    private val retryStates = loadRetryStates()
    private val _state =
        MutableStateFlow(
            ServerSyncState(
                pendingCount = pending.value.size,
                pendingOperations = pending.value,
            ),
        )
    val state: StateFlow<ServerSyncState> = _state.asStateFlow()
    val autoSync = MutableStateFlow(settings.getBoolean(AUTO_KEY, true))

    // Metadata and artwork mirroring are not implemented by this user-state synchronizer.
    // Expose them as disabled for cloud-snapshot compatibility instead of persisting no-op flags.
    val syncMetadata = MutableStateFlow(false)
    val syncProgress = progressPreferences.enabled
    val syncArtwork = MutableStateFlow(false)
    val syncFavorites = MutableStateFlow(settings.getBoolean(FAVORITES_KEY, true))
    private val appForeground = MutableStateFlow(false)
    private var automaticJob: Job? = null
    private var automaticScope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        if (automaticJob != null) return
        automaticScope = scope
        AppLog.info(
            category = "sync",
            event = "automatic_sync_started",
            message = "Automatic server synchronization started",
            attributes =
                mapOf(
                    "enabled" to autoSync.value.toString(),
                    "pendingCount" to pending.value.size.toString(),
                ),
        )
        automaticJob =
            scope.launch {
                appForeground
                    .collectLatest { foreground ->
                        if (!foreground) return@collectLatest
                        while (true) {
                            if (
                                autoSync.value &&
                                registry.data.value.servers
                                    .isNotEmpty()
                            ) {
                                syncAll()
                            }
                            delay(PERIOD_MS)
                        }
                    }
            }
    }

    /** Suspends automatic network and persistence work while the library UI is backgrounded. */
    fun setAppForeground(value: Boolean) {
        appForeground.value = value
    }

    fun setAutoSync(value: Boolean) {
        autoSync.value = value
        settings.putBoolean(AUTO_KEY, value)
        if (value && appForeground.value) automaticScope?.launch { syncAll() }
    }

    fun setMetadata(value: Boolean) {
        syncMetadata.value = false
        settings.remove(METADATA_KEY)
    }

    fun setProgress(value: Boolean) {
        progressPreferences.setEnabled(value)
        if (value && appForeground.value) automaticScope?.launch { syncAll() }
    }

    fun setArtwork(value: Boolean) {
        syncArtwork.value = false
        settings.remove(ARTWORK_KEY)
    }

    fun setFavorites(value: Boolean) {
        syncFavorites.value = value
        settings.putBoolean(FAVORITES_KEY, value)
        if (value && appForeground.value) automaticScope?.launch { syncAll() }
    }

    suspend fun syncCurrent() {
        syncMutex.withLock { registry.defaultServer?.let { sync(it) } }
    }

    /**
     * [force] bypasses the transient per-server hold-off, for a sync the user asked for by name.
     * Without it 立即同步 would silently do nothing for exactly the servers the user is
     * pressing it because of. Endpoints known to be permanently unavailable are still skipped
     * before HTTP, because forcing them can only repeat the same DNS failure.
     */
    suspend fun syncAll(force: Boolean = false) {
        syncMutex.withLock {
            registry.data.value.servers
                .forEach { sync(it, force) }
        }
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

    suspend fun resolveConflict(
        conflict: SyncConflict,
        keepLocal: Boolean,
    ): Result<Unit> {
        val server =
            registry.serverById(conflict.mutation.serverId)
                ?: return Result.failure(IllegalStateException("服务器已移除"))
        return if (keepLocal) {
            val mutation = conflict.mutation
            if (!kindEnabled(mutation.kind)) return Result.success(Unit)
            val result =
                when (mutation.kind) {
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
                }.onFailure {
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
        if (!kindEnabled(kind)) {
            return when (kind) {
                SyncMutationKind.Favorite -> repo.setFavorite(server, itemId, desired)
                // DetailComponent has already mirrored this explicit decision into the
                // device-local PlaybackSyncStore. The master switch forbids every remote
                // progress write, including Emby's played/unplayed endpoint.
                SyncMutationKind.Played -> Result.success(Unit)
            }
        }
        val base =
            snapshots[server.id]?.let { snapshot ->
                snapshot.firstOrNull { it.id == itemId }?.let {
                    when (kind) {
                        SyncMutationKind.Favorite -> it.favorite
                        SyncMutationKind.Played -> it.played
                    }
                } ?: false
            }
        val mutation =
            PendingSyncMutation(
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
            attributes =
                mapOf(
                    "serverId" to server.id,
                    "kind" to kind.name,
                    "desired" to desired.toString(),
                ),
        )
        // Re-check immediately before I/O in case the switch changed after this mutation
        // entered the durable queue. Keep it queued for a future explicit re-enable.
        if (!kindEnabled(kind)) return Result.success(Unit)
        val result =
            when (kind) {
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
                    attributes =
                        mapOf(
                            "serverId" to server.id,
                            "kind" to kind.name,
                        ),
                )
            }
        return result
    }

    private suspend fun sync(
        server: SavedServer,
        force: Boolean = false,
    ) {
        val hasEnabledPending =
            pending.value.any { it.serverId == server.id && kindEnabled(it.kind) }
        if (!syncFavorites.value && !syncProgress.value && !hasEnabledPending) return
        val unavailableReason = server.knownUnavailableEndpointReason()
        if (unavailableReason != null) {
            setStatus(server) {
                it.copy(
                    syncing = false,
                    online = false,
                    error = unavailableReason,
                )
            }
            AppLog.warning(
                category = "sync",
                event = "server_sync_skipped_unavailable_endpoint",
                message = "Server synchronization skipped for a known unavailable endpoint",
                attributes =
                    mapOf(
                        "serverId" to server.id,
                        "forced" to force.toString(),
                    ),
            )
            return
        }
        val now = System.currentTimeMillis()
        val retryState = retryStates[server.id]
        val notBefore = retryState?.retryNotBeforeEpochMs?.takeUnless { force }
        if (notBefore != null && now < notBefore) {
            AppLog.info(
                category = "sync",
                event = "server_sync_skipped",
                message = "Server synchronization skipped while backing off",
                attributes =
                    mapOf(
                        "serverId" to server.id,
                        "failureStreak" to retryState.failureStreak.toString(),
                        "retryInMs" to (notBefore - now).toString(),
                    ),
            )
            return
        }
        setStatus(server) { it.copy(syncing = true, error = null) }
        val snapshotResult =
            try {
                // Keep JSON decoding and page merging off the UI caller.
                withContext(Dispatchers.Default) {
                    repo.userLibrarySnapshot(server, includeProgress = syncProgress.value)
                }
            } catch (cancelled: CancellationException) {
                setStatus(server) { it.copy(syncing = false) }
                throw cancelled
            }
        snapshotResult.fold(
            onSuccess = { remote ->
                clearRetryState(server.id)
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
                _state.value =
                    _state.value.copy(
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
                        attributes =
                            mapOf(
                                "serverId" to server.id,
                                "conflictCount" to conflicts.size.toString(),
                            ),
                    )
                }
                AppLog.info(
                    category = "sync",
                    event = "server_sync_completed",
                    message = "Server synchronization completed",
                    attributes =
                        mapOf(
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
                val streak =
                    ((retryStates[server.id]?.failureStreak ?: 0) + 1)
                        .coerceAtMost(MAX_FAILURE_STREAK)
                val backoffMs = backoffFor(streak, unauthorized)
                commitRetryState(
                    ServerRetryState(
                        serverId = server.id,
                        failureStreak = streak,
                        retryNotBeforeEpochMs = System.currentTimeMillis() + backoffMs,
                    ),
                )
                AppLog.warning(
                    category = "sync",
                    event = "server_sync_failed",
                    message = "Server synchronization failed",
                    throwable = error,
                    attributes =
                        mapOf(
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
                        error =
                            when {
                                // A revoked token is not a network hiccup, and the old wording
                                // sent the user looking at their connection instead of re-signing in.
                                unauthorized -> "登录已失效，请重新登录该服务器"
                                // Never expose the exception's data-class representation (for
                                // example `AccessDenied(provider=Cloudflare)`) in the profile UI.
                                else -> error.toUserMessage("同步失败")
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
    private fun backoffFor(
        streak: Int,
        unauthorized: Boolean,
    ): Long {
        if (unauthorized) return UNAUTHORIZED_BACKOFF_MS
        val exponent = (streak - 1).coerceIn(0, 5)
        return (PERIOD_MS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun Throwable.isUnauthorized(): Boolean = (this as? EmbyErrorException)?.error == EmbyError.Unauthorized

    private fun commitRetryState(value: ServerRetryState) {
        retryStates[value.serverId] = value
        persistRetryStates()
    }

    private fun clearRetryState(serverId: String) {
        if (retryStates.remove(serverId) != null) persistRetryStates()
    }

    private fun persistRetryStates() {
        runCatching {
            if (retryStates.isEmpty()) {
                settings.remove(RETRY_STATE_KEY)
            } else {
                settings.putString(
                    RETRY_STATE_KEY,
                    json.encodeToString(retryStateSerializer, retryStates.values.toList()),
                )
            }
        }.onFailure {
            AppLog.error(
                category = "sync",
                event = "retry_state_persist_failed",
                message = "Failed to persist server synchronization backoff",
                throwable = it,
                attributes = mapOf("serverCount" to retryStates.size.toString()),
            )
        }
    }

    private fun loadRetryStates(): MutableMap<String, ServerRetryState> {
        val raw = settings.getStringOrNull(RETRY_STATE_KEY) ?: return mutableMapOf()
        val knownServerIds =
            registry.data.value.servers
                .mapTo(hashSetOf()) { it.id }
        val now = System.currentTimeMillis()
        return runCatching {
            linkedMapOf<String, ServerRetryState>().apply {
                json
                    .decodeFromString(retryStateSerializer, raw)
                    .filter { it.serverId in knownServerIds && it.failureStreak > 0 }
                    .forEach { state ->
                        this[state.serverId] =
                            state.copy(
                                failureStreak = state.failureStreak.coerceAtMost(MAX_FAILURE_STREAK),
                                // Protect against corrupted data or a wall clock moved far backwards.
                                retryNotBeforeEpochMs =
                                    state.retryNotBeforeEpochMs.coerceAtMost(
                                        now + UNAUTHORIZED_BACKOFF_MS,
                                    ),
                            )
                    }
            }
        }.onFailure {
            AppLog.error(
                category = "sync",
                event = "stored_retry_state_invalid",
                message = "Stored server synchronization backoff could not be decoded",
                throwable = it,
            )
        }.getOrDefault(mutableMapOf())
    }

    private suspend fun replayNonConflicting(
        server: SavedServer,
        conflicts: List<SyncConflict>,
    ) {
        val blocked = conflicts.map { it.mutation }.toSet()
        pending.value
            .filter { it.serverId == server.id && it !in blocked && kindEnabled(it.kind) }
            .forEach { mutation ->
                if (!kindEnabled(mutation.kind)) return@forEach
                val result =
                    when (mutation.kind) {
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
                            attributes =
                                mapOf(
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
    ): List<SyncConflict> {
        val remoteById = remote.associateBy(SyncedUserItem::id)
        return pending.value
            .filter { it.serverId == serverId && kindEnabled(it.kind) }
            .mapNotNull { mutation ->
                val item = remoteById[mutation.itemId]
                val remoteValue =
                    when (mutation.kind) {
                        SyncMutationKind.Favorite -> item?.favorite == true
                        SyncMutationKind.Played -> item?.played == true
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
    }

    private fun kindEnabled(kind: SyncMutationKind): Boolean =
        when (kind) {
            SyncMutationKind.Favorite -> syncFavorites.value
            SyncMutationKind.Played -> syncProgress.value
        }

    private fun setStatus(
        server: SavedServer,
        transform: (ServerSyncStatus) -> ServerSyncStatus,
    ) {
        val old =
            _state.value.statuses.firstOrNull { it.serverId == server.id }
                ?: ServerSyncStatus(server.id, server.serverName)
        _state.value =
            _state.value.copy(
                statuses =
                    _state.value.statuses.filterNot { it.serverId == server.id } +
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
        _state.value =
            _state.value.copy(
                pendingCount = pending.value.size,
                pendingOperations = pending.value,
                conflicts = _state.value.conflicts.filterNot { it.mutation == mutation },
            )
    }

    private fun commitPending(value: List<PendingSyncMutation>) {
        val bounded = boundPendingMutations(value)
        if (bounded.size < value.size) {
            AppLog.warning(
                category = "sync",
                event = "pending_queue_trimmed",
                message = "Synchronization queue reached its storage budget; oldest operations were removed",
                attributes =
                    mapOf(
                        "requestedCount" to value.size.toString(),
                        "storedCount" to bounded.size.toString(),
                    ),
            )
        }
        pending.value = bounded
        runCatching {
            if (bounded.isEmpty()) {
                settings.remove(PENDING_KEY)
            } else {
                settings.putString(PENDING_KEY, json.encodeToString(pendingSerializer, bounded))
            }
        }.onFailure {
            AppLog.error(
                category = "sync",
                event = "pending_persist_failed",
                message = "Failed to persist queued synchronization mutations",
                throwable = it,
                attributes = mapOf("pendingCount" to bounded.size.toString()),
            )
        }
        _state.value =
            _state.value.copy(
                pendingCount = bounded.size,
                pendingOperations = bounded,
            )
    }

    private fun loadPending(): List<PendingSyncMutation> {
        val raw = settings.getStringOrNull(PENDING_KEY) ?: return emptyList()
        if (
            raw.length > MAX_PENDING_SERIALIZED_BYTES ||
            raw.encodeToByteArray().size > MAX_PENDING_SERIALIZED_BYTES
        ) {
            settings.remove(PENDING_KEY)
            AppLog.warning(
                category = "sync",
                event = "stored_pending_oversized",
                message = "Stored synchronization queue exceeded its byte budget and was cleared",
                attributes = mapOf("storedChars" to raw.length.toString()),
            )
            return emptyList()
        }
        return runCatching {
            boundPendingMutations(json.decodeFromString(pendingSerializer, raw))
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
