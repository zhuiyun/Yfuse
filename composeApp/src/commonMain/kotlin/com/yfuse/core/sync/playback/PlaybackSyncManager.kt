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
    private var cloudFailureStreak = 0
    private var retryNotBeforeEpochMs = Long.MIN_VALUE
    private var cloudPlaybackEndpointUnavailable = false
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
                durationMs > 0L &&
                positionMs >= (durationMs * COMPLETED_RATIO).toLong()
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
                if (completed) {
                    PlaybackMutationKind.AutoFinished
                } else {
                    PlaybackMutationKind.AutoProgress
                },
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
        serverId: String? = null,
        transform: (PlaybackTrackPreference?) -> PlaybackTrackPreference,
    ) {
        if (store.updatePreference(mediaKey, aliases, serverId, transform) == null) return
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
        serverId: String? = null,
    ): Long? =
        store.authoritativeStartPositionMs(
            mediaKey = mediaKey,
            aliases = aliases,
            serverId = serverId,
            completedRatio = COMPLETED_RATIO,
        )

    fun resumePositionMs(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        serverId: String? = null,
    ): Long? = startPositionMs(mediaKey, aliases, serverId)?.takeIf { it > 0L }

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
            drainServerApplyQueue()
            if (cloudPlaybackEndpointUnavailable) return
            val accessToken = accessTokens.validAccessTokenFor(cloud.origin) ?: return
            val now = nowEpochMs()
            if (now < retryNotBeforeEpochMs) return
            lastCloudAttemptAtEpochMs = now
            _state.value = _state.value.copy(syncing = true, error = null)
            try {
                syncWithToken(accessToken)
                markCloudSyncSucceeded()
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
                                markCloudSyncSucceeded()
                                _state.value =
                                    _state.value.copy(
                                        syncing = false,
                                        pendingCount = store.pending(128).size,
                                        cursor = store.cursor(),
                                        lastSyncedAtEpochMs = nowEpochMs(),
                                        error = null,
                                    )
                            }.onFailure(::handleCloudFailure)
                        return
                    }
                }
                handleCloudFailure(error)
            } catch (error: Throwable) {
                recordFailure(error)
            }
        }
    }

    private suspend fun syncWithToken(accessToken: String) {
        pullAll(accessToken)
        pushPending(accessToken)
        drainServerApplyQueue()
    }

    private suspend fun pullAll(accessToken: String) {
        var pages = 0
        do {
            val response = cloud.pull(accessToken, store.cursor(), PULL_PAGE_SIZE)
            response.changes.forEach { encrypted ->
                val document =
                    cipher.decrypt(encrypted)
                        ?: throw PlaybackEntityDecryptException(encrypted.entityKey)
                val applied =
                    store.applyRemote(
                        remote = document,
                        entityKey = encrypted.entityKey,
                        cursor = encrypted.cursor,
                    )
                if (applied.changedLocal && document.state.deviceId != store.deviceId) {
                    store.enqueueServerApply(
                        document = applied.document,
                        serverIds = serverApplier.targetServerIds(applied.document),
                    )
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
                    serverId = local.document.state.serverId,
                    entityKey = accepted.entityKey,
                    mutationId = accepted.mutationId,
                    cursor = accepted.cursor,
                )
            }
            response.conflicts.forEach { conflict ->
                val remote =
                    cipher.decrypt(conflict)
                        ?: throw PlaybackEntityDecryptException(conflict.entityKey)
                val applied =
                    store.applyRemote(
                        remote = remote,
                        entityKey = conflict.entityKey,
                        cursor = conflict.cursor,
                    )
                if (applied.changedLocal && remote.state.deviceId != store.deviceId) {
                    store.enqueueServerApply(
                        document = applied.document,
                        serverIds = serverApplier.targetServerIds(applied.document),
                    )
                }
            }
        }
    }

    private suspend fun drainServerApplyQueue() {
        repeat(MAX_SERVER_APPLIES_PER_SYNC) {
            val task = store.pendingServerApplies(nowEpochMs(), limit = 1).firstOrNull() ?: return
            val serverId = task.remainingServerIds.firstOrNull()
            if (serverId == null || registryServerMissing(serverId)) {
                serverId?.let { store.markServerApplySucceeded(task.id, it) }
                return@repeat
            }
            val result = serverApplier.apply(task.document, serverId)
            if (result.isSuccess) {
                store.markServerApplySucceeded(task.id, serverId)
            } else {
                val nextAttempt =
                    nowEpochMs() + playbackServerApplyBackoffMs(task.attemptCount + 1)
                store.deferServerApply(task.id, nextAttempt)
                AppLog.warning(
                    category = "playback.sync",
                    event = "server_apply_deferred",
                    message = "Cloud playback state remains queued for a media server",
                    throwable = result.exceptionOrNull(),
                    attributes =
                        mapOf(
                            "serverId" to serverId,
                            "attempt" to (task.attemptCount + 1).toString(),
                            "pendingCount" to store.serverApplyCount().toString(),
                        ),
                )
                return
            }
        }
    }

    private fun registryServerMissing(serverId: String): Boolean = serverApplier.serverMissing(serverId)

    private fun scheduleCloudSync(immediate: Boolean) {
        if (cloudPlaybackEndpointUnavailable) return
        if (!accessTokens.sessionAvailable.value) return
        if (immediate) {
            debounceJob?.cancel()
            debounceJob = null
            if (urgentJob?.isActive == true) return
            urgentJob =
                scope.launch {
                    val now = nowEpochMs()
                    val elapsed =
                        if (lastCloudAttemptAtEpochMs == Long.MIN_VALUE) {
                            Long.MAX_VALUE
                        } else {
                            (now - lastCloudAttemptAtEpochMs).coerceAtLeast(0L)
                        }
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

    private fun handleCloudFailure(error: Throwable) {
        if (playbackCloudEndpointUnavailable(error)) {
            markCloudPlaybackEndpointUnavailable(error as AccountApiException)
        } else {
            recordFailure(error)
        }
    }

    private fun markCloudPlaybackEndpointUnavailable(error: AccountApiException) {
        if (cloudPlaybackEndpointUnavailable) return
        cloudPlaybackEndpointUnavailable = true
        cloudFailureStreak = 0
        retryNotBeforeEpochMs = Long.MAX_VALUE
        val pendingCount = store.pending(128).size
        val attributes =
            mapOf(
                "status" to error.status.value.toString(),
                "code" to error.code.take(64),
                "pendingCount" to pendingCount.toString(),
            )
        if (pendingCount == 0) {
            AppLog.info(
                category = "playback.sync",
                event = "cloud_endpoint_unavailable",
                message = "Cloud playback synchronization is not enabled; no local records are pending",
                attributes = attributes,
            )
        } else {
            AppLog.warning(
                category = "playback.sync",
                event = "cloud_endpoint_unavailable",
                message = "Cloud playback synchronization is not enabled; local records remain queued",
                attributes = attributes,
            )
        }
        _state.value =
            _state.value.copy(
                syncing = false,
                pendingCount = pendingCount,
                cursor = store.cursor(),
                error =
                    "云端播放记录同步尚未启用，本地记录已保留"
                        .takeIf { pendingCount > 0 },
            )
    }

    private fun recordFailure(error: Throwable) {
        cloudFailureStreak = (cloudFailureStreak + 1).coerceAtMost(MAX_CLOUD_FAILURE_STREAK)
        val backoffMs = playbackCloudRetryBackoffMs(cloudFailureStreak)
        retryNotBeforeEpochMs = nowEpochMs() + backoffMs
        val apiError = error as? AccountApiException
        AppLog.warning(
            category = "playback.sync",
            event = "cloud_sync_failed",
            message = "Cross-platform playback synchronization was deferred",
            throwable = error,
            attributes =
                buildMap {
                    put("failureStreak", cloudFailureStreak.toString())
                    put("backoffMs", backoffMs.toString())
                    apiError?.let {
                        put("status", it.status.value.toString())
                        put("code", it.code.take(64))
                    }
                },
        )
        _state.value =
            _state.value.copy(
                syncing = false,
                pendingCount = store.pending(128).size,
                cursor = store.cursor(),
                error = error.message ?: "播放记录同步暂不可用",
            )
    }

    private fun markCloudSyncSucceeded() {
        cloudFailureStreak = 0
        retryNotBeforeEpochMs = Long.MIN_VALUE
    }

    private companion object {
        const val CLOUD_DEBOUNCE_MS = 20_000L
        const val MIN_URGENT_CLOUD_GAP_MS = 5_000L
        const val MAX_CLOUD_FAILURE_STREAK = 6
        const val PERIODIC_CLOUD_SYNC_MS = 60_000L
        const val PREPLAY_SYNC_MAX_AGE_MS = 10_000L
        const val PREPLAY_SYNC_BUDGET_MS = 1_000L
        const val PULL_PAGE_SIZE = 100
        const val MAX_PULL_PAGES_PER_SYNC = 8
        const val PUSH_BATCH_SIZE = 8
        const val MAX_PUSH_ROUNDS = 2
        const val MAX_SERVER_APPLIES_PER_SYNC = 16
        const val COMPLETED_RATIO = 0.95
    }
}

internal fun playbackCloudRetryBackoffMs(failureStreak: Int): Long {
    val exponent = (failureStreak - 1).coerceIn(0, 5)
    return (30_000L * (1L shl exponent)).coerceAtMost(15 * 60_000L)
}

internal fun playbackServerApplyBackoffMs(failureStreak: Int): Long {
    val exponent = (failureStreak - 1).coerceIn(0, 6)
    return (15_000L * (1L shl exponent)).coerceAtMost(30 * 60_000L)
}

private class PlaybackEntityDecryptException(
    entityKey: String,
) : IllegalStateException("无法解密云端播放记录，已保留游标等待重试：${entityKey.take(12)}")

internal fun playbackCloudEndpointUnavailable(error: Throwable): Boolean =
    error is AccountApiException && error.status == HttpStatusCode.NotFound

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
    fun serverMissing(serverId: String): Boolean = registry.serverById(serverId) == null

    fun targetServerIds(document: PlaybackSyncDocument): List<String> {
        val state = document.state
        val keys = listOf(state.mediaKey) + state.aliases
        val hasPortableIdentity = keys.any { !it.startsWith("emby:", ignoreCase = true) }
        return if (hasPortableIdentity) {
            registry.data.value.servers
                .map { it.id }
        } else {
            listOfNotNull(state.serverId?.takeIf { registry.serverById(it) != null })
        }
    }

    suspend fun apply(
        document: PlaybackSyncDocument,
        serverId: String,
    ): Result<Unit> =
        runCatching {
            val state = document.state
            val server = registry.serverById(serverId) ?: return@runCatching
            val item =
                (listOf(state.mediaKey) + state.aliases)
                    .firstNotNullOfOrNull { key -> repo.findByMediaKey(server, key).getOrThrow() }
                    ?: return@runCatching
            val isOrigin = server.id == state.serverId && item.id == state.serverItemId
            if (isOrigin && state.mutationKind != PlaybackMutationKind.ManualUnwatched) {
                return@runCatching
            }
            when (state.mutationKind) {
                PlaybackMutationKind.ManualWatched,
                PlaybackMutationKind.AutoFinished,
                -> repo.setPlayed(server, item.id, true).getOrThrow()
                PlaybackMutationKind.ManualUnwatched ->
                    resetServerProgress(server, item.id, state.deviceId).getOrThrow()
                PlaybackMutationKind.AutoProgress -> {
                    when {
                        state.positionMs > 0L -> {
                            repo
                                .reportPlaybackStopped(
                                    server = server,
                                    itemId = item.id,
                                    playSessionId = "yfuse-cloud-${state.deviceId.takeLast(12)}",
                                    positionTicks =
                                        state.positionMs.coerceAtMost(Long.MAX_VALUE / 10_000L) *
                                            10_000L,
                                    isPaused = true,
                                ).getOrThrow()
                        }
                        state.progressEpoch > 0L ->
                            resetServerProgress(server, item.id, state.deviceId).getOrThrow()
                        else -> Unit
                    }
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
