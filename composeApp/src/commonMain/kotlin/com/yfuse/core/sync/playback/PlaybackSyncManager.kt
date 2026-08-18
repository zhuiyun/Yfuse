package com.yfuse.core.sync.playback

import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApiException
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.PlaybackVaultCipher
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull


data class PlaybackCloudSyncState(
    val syncing: Boolean = false,
    val pendingCount: Int = 0,
    val cursor: Long = 0L,
    val lastSyncedAtEpochMs: Long? = null,
    val error: String? = null,
)

/**
 * Local-first cross-platform playback synchronization.
 *
 * Media identities and playback metadata are encrypted before leaving the device. The account
 * service sees only a vault-keyed entity tag, a cursor and an AES-GCM envelope.
 */
class PlaybackSyncManager(
    private val store: PlaybackSyncStore,
    private val cloud: PlaybackCloudApi,
    private val cipher: PlaybackVaultCipher,
    private val accessTokens: AccountAccessTokenSource,
    repo: EmbyRepository,
    registry: ServerRegistry,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private val serverApplier = EmbyCompatiblePlaybackStateApplier(repo, registry)
    private var started = false
    private var debounceJob: Job? = null
    private var urgentJob: Job? = null
    private var lastCloudAttemptAtEpochMs = Long.MIN_VALUE
    private val _state =
        MutableStateFlow(
            PlaybackCloudSyncState(
                pendingCount = store.pending(128).size,
                cursor = store.cursor(),
            ),
        )
    val state: StateFlow<PlaybackCloudSyncState> = _state.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            accessTokens.sessionAvailable.collectLatest { available ->
                if (!available) return@collectLatest
                val userId = cipher.currentUserId() ?: return@collectLatest
                if (store.bindAccount(userId)) updatePendingState()
                syncNow()
                while (true) {
                    delay(PERIODIC_CLOUD_SYNC_MS)
                    syncNow()
                }
            }
        }
    }

    fun recordPlayback(
        mediaKey: String,
        aliases: List<String>,
        positionMs: Long,
        durationMs: Long,
        sessionId: String?,
        serverId: String?,
        serverItemId: String?,
        trigger: PlaybackSyncTrigger,
    ) {
        if (mediaKey.isBlank()) return
        val completed =
            trigger == PlaybackSyncTrigger.Completed ||
                durationMs > 0L && positionMs >= (durationMs * COMPLETED_RATIO).toLong()
        store.updatePlayback(
            mediaKey = mediaKey,
            aliases = aliases,
            positionMs = positionMs,
            durationMs = durationMs,
            played = completed,
            sessionId = sessionId,
            serverId = serverId,
            serverItemId = serverItemId,
            mutationKind =
                if (completed) PlaybackMutationKind.AutoFinished
                else PlaybackMutationKind.AutoProgress,
            trigger = trigger,
        )
        updatePendingState()
        scheduleCloudSync(trigger.isImmediateCloudTrigger)
    }

    fun markRestarted(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        serverId: String? = null,
        serverItemId: String? = null,
    ) {
        if (mediaKey.isBlank()) return
        store.markRestarted(mediaKey, aliases, serverId, serverItemId)
        updatePendingState()
        scheduleCloudSync(immediate = true)
    }

    fun markWatched(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        watched: Boolean,
        serverId: String? = null,
        serverItemId: String? = null,
    ) {
        if (mediaKey.isBlank()) return
        store.markManual(mediaKey, aliases, watched, serverId, serverItemId)
        updatePendingState()
        scheduleCloudSync(immediate = true)
    }

    fun updatePreference(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        transform: (PlaybackTrackPreference?) -> PlaybackTrackPreference,
    ) {
        if (store.updatePreference(mediaKey, aliases, transform) == null) return
        updatePendingState()
        scheduleCloudSync(immediate = false)
    }

    /**
     * The account-level position to use for an ordinary playback launch.
     *
     * `null` means Yfuse has no opinion and the media server's start position remains authoritative.
     * A real synced record may deliberately resolve to `0` (finished, manually unwatched, or a
     * newer restart), so callers must not collapse zero into the no-record case.
     */
    fun startPositionMs(
        mediaKey: String,
        aliases: List<String> = emptyList(),
    ): Long? =
        store.authoritativeStartPositionMs(
            mediaKey = mediaKey,
            aliases = aliases,
            completedRatio = COMPLETED_RATIO,
        )

    fun resumePositionMs(
        mediaKey: String,
        aliases: List<String> = emptyList(),
    ): Long? = startPositionMs(mediaKey, aliases)?.takeIf { it > 0L }

    /**
     * Best-effort pull immediately before an ordinary resume, closing the small gap between the
     * 60-second background poll and a user who just moved from another device. Playback never
     * waits on a slow/offline account service longer than [budgetMs].
     */
    suspend fun refreshForPlayback(
        maxAgeMs: Long = PREPLAY_SYNC_MAX_AGE_MS,
        budgetMs: Long = PREPLAY_SYNC_BUDGET_MS,
    ) {
        if (maxAgeMs < 0L || budgetMs <= 0L) return
        val lastSuccess = _state.value.lastSyncedAtEpochMs
        if (lastSuccess != null && nowEpochMs() - lastSuccess in 0L..maxAgeMs) return
        withTimeoutOrNull(budgetMs) { syncNow() }
    }

    suspend fun syncNow() {
        syncMutex.withLock {
            val userId = cipher.currentUserId() ?: return
            if (store.bindAccount(userId)) updatePendingState()
            val accessToken = accessTokens.validAccessTokenFor(cloud.origin) ?: return
            lastCloudAttemptAtEpochMs = nowEpochMs()
            _state.value = _state.value.copy(syncing = true, error = null)
            try {
                syncWithToken(accessToken)
                _state.value =
                    _state.value.copy(
                        syncing = false,
                        pendingCount = store.pending(128).size,
                        cursor = store.cursor(),
                        lastSyncedAtEpochMs = nowEpochMs(),
                        error = null,
                    )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(syncing = false)
                throw cancelled
            } catch (error: AccountApiException) {
                if (error.status == HttpStatusCode.Unauthorized) {
                    val refreshed = accessTokens.refreshAccessTokenFor(cloud.origin)
                    if (refreshed != null) {
                        runCatching { syncWithToken(refreshed) }
                            .onSuccess {
                                _state.value =
                                    _state.value.copy(
                                        syncing = false,
                                        pendingCount = store.pending(128).size,
                                        cursor = store.cursor(),
                                        lastSyncedAtEpochMs = nowEpochMs(),
                                        error = null,
                                    )
                            }.onFailure(::recordFailure)
                        return
                    }
                }
                recordFailure(error)
            } catch (error: Throwable) {
                recordFailure(error)
            }
        }
    }

    private suspend fun syncWithToken(accessToken: String) {
        pullAll(accessToken)
        pushPending(accessToken)
    }

    private suspend fun pullAll(accessToken: String) {
        var pages = 0
        do {
            val response = cloud.pull(accessToken, store.cursor(), PULL_PAGE_SIZE)
            response.changes.forEach { encrypted ->
                val document = cipher.decrypt(encrypted) ?: return@forEach
                val applied =
                    store.applyRemote(
                        remote = document,
                        entityKey = encrypted.entityKey,
                        cursor = encrypted.cursor,
                    )
                if (applied.changedLocal && document.state.deviceId != store.deviceId) {
                    scope.launch { serverApplier.apply(applied.document) }
                }
            }
            store.updateCursor(response.cursor)
            pages++
        } while (response.hasMore && pages < MAX_PULL_PAGES_PER_SYNC)
    }

    private suspend fun pushPending(accessToken: String) {
        repeat(MAX_PUSH_ROUNDS) {
            val pending = store.pending(PUSH_BATCH_SIZE)
            if (pending.isEmpty()) return
            val prepared =
                pending.mapNotNull { stored ->
                    cipher.encrypt(stored.document, stored.mutationId)?.let { encrypted ->
                        val baseCursor = stored.remoteCursors[encrypted.entityKey] ?: 0L
                        stored to PlaybackPutItem(baseCursor, encrypted)
                    }
                }
            if (prepared.isEmpty()) return
            val response =
                cloud.push(
                    accessToken,
                    PlaybackPushRequest(prepared.map { it.second }),
                )
            response.accepted.forEach { accepted ->
                val local =
                    prepared.firstOrNull { it.second.entity.entityKey == accepted.entityKey }?.first
                        ?: return@forEach
                store.markUploaded(
                    mediaKey = local.document.state.mediaKey,
                    aliases = local.document.state.aliases,
                    entityKey = accepted.entityKey,
                    mutationId = accepted.mutationId,
                    cursor = accepted.cursor,
                )
            }
            response.conflicts.forEach { conflict ->
                val remote = cipher.decrypt(conflict) ?: return@forEach
                val applied =
                    store.applyRemote(
                        remote = remote,
                        entityKey = conflict.entityKey,
                        cursor = conflict.cursor,
                    )
                if (applied.changedLocal && remote.state.deviceId != store.deviceId) {
                    scope.launch { serverApplier.apply(applied.document) }
                }
            }
            store.updateCursor(response.cursor)
        }
    }

    private fun scheduleCloudSync(immediate: Boolean) {
        if (!accessTokens.sessionAvailable.value) return
        if (immediate) {
            debounceJob?.cancel()
            debounceJob = null
            if (urgentJob?.isActive == true) return
            urgentJob =
                scope.launch {
                    val now = nowEpochMs()
                    val elapsed =
                        if (lastCloudAttemptAtEpochMs == Long.MIN_VALUE) Long.MAX_VALUE
                        else (now - lastCloudAttemptAtEpochMs).coerceAtLeast(0L)
                    if (elapsed < MIN_URGENT_CLOUD_GAP_MS) {
                        delay(MIN_URGENT_CLOUD_GAP_MS - elapsed)
                    }
                    syncNow()
                }
            return
        }
        if (debounceJob?.isActive == true || urgentJob?.isActive == true) return
        debounceJob =
            scope.launch {
                delay(CLOUD_DEBOUNCE_MS)
                syncNow()
            }
    }

    private fun updatePendingState() {
        _state.value =
            _state.value.copy(
                pendingCount = store.pending(128).size,
                cursor = store.cursor(),
            )
    }

    private fun recordFailure(error: Throwable) {
        AppLog.warning(
            category = "playback.sync",
            event = "cloud_sync_failed",
            message = "Cross-platform playback synchronization was deferred",
            throwable = error,
        )
        _state.value =
            _state.value.copy(
                syncing = false,
                pendingCount = store.pending(128).size,
                cursor = store.cursor(),
                error = error.message ?: "播放记录同步暂不可用",
            )
    }

    private companion object {
        const val CLOUD_DEBOUNCE_MS = 20_000L
        const val MIN_URGENT_CLOUD_GAP_MS = 3_000L
        const val PERIODIC_CLOUD_SYNC_MS = 60_000L
        const val PREPLAY_SYNC_MAX_AGE_MS = 10_000L
        const val PREPLAY_SYNC_BUDGET_MS = 1_000L
        const val PULL_PAGE_SIZE = 100
        const val MAX_PULL_PAGES_PER_SYNC = 8
        const val PUSH_BATCH_SIZE = 8
        const val MAX_PUSH_ROUNDS = 1
        const val COMPLETED_RATIO = 0.95
    }
}

private val PlaybackSyncTrigger.isImmediateCloudTrigger: Boolean
    get() =
        this in
            setOf(
                PlaybackSyncTrigger.Pause,
                PlaybackSyncTrigger.Seek,
                PlaybackSyncTrigger.Stop,
                PlaybackSyncTrigger.Background,
                PlaybackSyncTrigger.Completed,
                PlaybackSyncTrigger.Manual,
            )

/** Native server fan-out. The abstraction point is intentionally provider-neutral for Plex later. */
private class EmbyCompatiblePlaybackStateApplier(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
) {
    suspend fun apply(document: PlaybackSyncDocument) {
        val state = document.state
        registry.data.value.servers.forEach { server ->
            val item =
                (listOf(state.mediaKey) + state.aliases)
                    .asSequence()
                    .mapNotNull { key -> repo.findByMediaKey(server, key).getOrNull() }
                    .firstOrNull()
                    ?: return@forEach
            val isOrigin = server.id == state.serverId && item.id == state.serverItemId
            if (isOrigin && state.mutationKind != PlaybackMutationKind.ManualUnwatched) {
                return@forEach
            }
            when (state.mutationKind) {
                PlaybackMutationKind.ManualWatched,
                PlaybackMutationKind.AutoFinished,
                -> repo.setPlayed(server, item.id, true)
                PlaybackMutationKind.ManualUnwatched -> resetServerProgress(server, item.id, state.deviceId)
                PlaybackMutationKind.AutoProgress -> {
                    when {
                        state.positionMs > 0L ->
                            repo.reportPlaybackStopped(
                                server = server,
                                itemId = item.id,
                                playSessionId = "yfuse-cloud-${state.deviceId.takeLast(12)}",
                                positionTicks =
                                    state.positionMs.coerceAtMost(Long.MAX_VALUE / 10_000L) * 10_000L,
                                isPaused = true,
                            )
                        state.progressEpoch > 0L -> resetServerProgress(server, item.id, state.deviceId)
                        else -> Result.success(Unit)
                    }
                }
            }.onFailure { error ->
                AppLog.warning(
                    category = "playback.sync",
                    event = "server_apply_failed",
                    message = "Cloud playback state could not be applied to a media server",
                    throwable = error,
                    attributes = mapOf("serverId" to server.id),
                )
            }
        }
    }

    private suspend fun resetServerProgress(
        server: com.yfuse.core.model.SavedServer,
        itemId: String,
        deviceId: String,
    ): Result<Unit> =
        repo.setPlayed(server, itemId, false).fold(
            onSuccess = {
                repo.reportPlaybackStopped(
                    server = server,
                    itemId = itemId,
                    playSessionId = "yfuse-cloud-reset-${deviceId.takeLast(12)}",
                    positionTicks = 0L,
                    isPaused = true,
                )
            },
            onFailure = { Result.failure(it) },
        )
}
