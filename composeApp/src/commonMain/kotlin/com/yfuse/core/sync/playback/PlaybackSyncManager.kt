package com.yfuse.core.sync.playback

import com.russhwolf.settings.Settings
import com.yfuse.core.account.AccountAccessTokenSource
import com.yfuse.core.account.AccountApiException
import com.yfuse.core.account.PlaybackCloudApi
import com.yfuse.core.account.PlaybackVaultCipher
import com.yfuse.core.data.EmbyRepository
import com.yfuse.core.data.ServerHealthMonitor
import com.yfuse.core.data.ServerRegistry
import com.yfuse.core.logging.AppLog
import com.yfuse.core.network.EmbyError
import com.yfuse.core.network.EmbyErrorException
import com.yfuse.core.personal.PersonalAccessPolicy
import com.yfuse.core.personal.PersonalLibraryRepository
import com.yfuse.feature.player.PlaybackLaunchTimings
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

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
    private val progressSyncEnabled: StateFlow<Boolean> = MutableStateFlow(true),
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val personal: PersonalLibraryRepository? = null,
    /** Null keeps every server eligible, as this manager always did before the monitor existed. */
    serverHealth: ServerHealthMonitor? = null,
    /** Null keeps the per-server backoff in memory only, as it always was before this. */
    settings: Settings? = null,
) {
    private val syncMutex = Mutex()
    private val serverApplier =
        EmbyCompatiblePlaybackStateApplier(repo, registry, nowEpochMs, personal, serverHealth, settings)
    private val sessionOwners = mutableMapOf<String, String>()

    /**
     * Guards the job slots, the startup-pull sets and every compound update of the backoff
     * fields below. Playback events arrive on the player's thread while the session collector
     * and each sync run on [scope]; unguarded, two of them could both find a slot empty and
     * launch duplicate urgent or retry jobs. Never held across a suspension point.
     */
    private val scheduleLock = Any()
    private var lastForegroundRefresh = Long.MIN_VALUE

    /** Explicit recovery may retry a previously unavailable endpoint; it never blocks playback. */
    fun refreshNow() {
        scope.launch {
            synchronized(scheduleLock) {
                cloudPlaybackEndpointUnavailable = false
                retryNotBeforeEpochMs = Long.MIN_VALUE
            }
            syncNow(pullRemote = true)
        }
    }

    fun setAppForeground(foreground: Boolean) {
        if (!foreground) {
            // Periodic progress is coalesced in memory, and a backgrounded process may not come
            // back. Launched rather than run here: this is a lifecycle callback on the main
            // thread and the write is a full encode of the local history.
            scope.launch { store.flush() }
            return
        }
        val now = nowEpochMs()
        synchronized(scheduleLock) {
            if (lastForegroundRefresh != Long.MIN_VALUE && now - lastForegroundRefresh < 30_000) return
            lastForegroundRefresh = now
        }
        scope.launch { syncNow(pullRemote = true) }
    }

    private var started = false
    private var debounceJob: Job? = null
    private var urgentJob: Job? = null
    private var retryJob: Job? = null
    private var serverRetryJob: Job? = null
    private var cloudFailureStreak = 0

    // Written under [scheduleLock] or [syncMutex]; single reads also happen outside both, on the
    // scheduling fast path and inside a launched job.
    @Volatile private var lastCloudAttemptAtEpochMs = Long.MIN_VALUE

    @Volatile private var retryNotBeforeEpochMs = Long.MIN_VALUE

    @Volatile private var cloudPlaybackEndpointUnavailable = false
    private val startupPullAttemptedUserIds = mutableSetOf<String>()
    private val startupPullPendingUserIds = mutableSetOf<String>()
    private val _state =
        MutableStateFlow(
            PlaybackCloudSyncState(
                pendingCount = store.pending(128).size,
                cursor = store.cursor(),
            ),
        )
    val state: StateFlow<PlaybackCloudSyncState> = _state.asStateFlow()

    fun start() {
        synchronized(scheduleLock) {
            if (started) return
            started = true
        }
        scope.launch {
            combine(
                accessTokens.sessionAvailable,
                progressSyncEnabled,
                personal?.policy ?: MutableStateFlow(PersonalAccessPolicy()),
            ) { sessionAvailable, enabled, _ ->
                sessionAvailable && enabled
            }.collectLatest { active ->
                if (!active) {
                    cancelScheduledJobs()
                    _state.update { it.copy(syncing = false) }
                    return@collectLatest
                }
                val userId = cipher.currentUserId() ?: return@collectLatest
                if (store.bindAccount(userId)) updatePendingState()
                synchronized(scheduleLock) {
                    if (startupPullAttemptedUserIds.add(userId)) startupPullPendingUserIds.add(userId)
                }
                syncNow()
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
        synchronized(personal?.coordinationLock ?: sessionOwners) {
            if (mediaKey.isBlank()) return
            if (personal != null) {
                val token = personal.scopeToken
                val owner =
                    if (sessionId == null) {
                        token
                    } else {
                        synchronized(sessionOwners) {
                            sessionOwners.getOrPut(sessionId) { token }.also {
                                if (sessionOwners.size >
                                    128
                                ) {
                                    sessionOwners.keys.firstOrNull()?.let(sessionOwners::remove)
                                }
                            }
                        }
                    }
                if (owner != token || serverId?.let { !personal.canAccessServer(it) } == true) return
            }
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

    /**
     * 从继续观看移除. The shelf is every local record with a position, so the title leaves it by
     * starting over — a new generation, as [markRestarted] makes, so a late report from the last
     * viewing cannot put it back. False when this device has no record of the item.
     */
    fun forgetResume(
        serverId: String,
        serverItemId: String,
    ): Boolean {
        val state = store.stateForServerItem(serverId, serverItemId) ?: return false
        markRestarted(state.mediaKey, state.aliases, serverId, serverItemId)
        return true
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
     * The process-local position to use for an ordinary playback launch.
     *
     * The startup pull may seed this store once, but playback itself never performs a remote read.
     * `null` means Yfuse has no local record. A real record may deliberately resolve to `0`
     * (finished, manually unwatched, or a newer restart), so callers must not collapse zero into
     * the no-record case.
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

    private suspend fun syncNow(pullRemote: Boolean = false) {
        syncMutex.withLock {
            if (!progressSyncEnabled.value || !accessTokens.sessionAvailable.value) {
                _state.update { it.copy(syncing = false) }
                return
            }
            val userId = cipher.currentUserId() ?: return
            if (store.bindAccount(userId)) updatePendingState()
            val shouldPull =
                pullRemote || synchronized(scheduleLock) { userId in startupPullPendingUserIds }
            drainServerApplyQueue()
            if (cloudPlaybackEndpointUnavailable) return
            // Token acquisition can itself refresh over the network. Respect the cloud
            // backoff before touching it, not only before the playback endpoint request.
            val now = nowEpochMs()
            val retryNotBefore = retryNotBeforeEpochMs
            if (now < retryNotBefore) {
                scheduleCloudRetry(retryNotBefore - now)
                return
            }
            lastCloudAttemptAtEpochMs = now
            _state.update { it.copy(syncing = true, error = null) }
            try {
                val accessToken = accountTokenForSync(refresh = false) ?: return
                try {
                    syncWithToken(accessToken, shouldPull)
                } catch (error: AccountApiException) {
                    if (error.status != HttpStatusCode.Unauthorized) throw error
                    val refreshed = accountTokenForSync(refresh = true) ?: return
                    // Retry exactly once. A second rejection reaches the outer failure
                    // boundary instead of refreshing forever or escaping a catch block.
                    syncWithToken(refreshed, shouldPull)
                }
                markCloudSyncSucceeded()
                _state.value =
                    _state.value.copy(
                        syncing = false,
                        pendingCount = store.pending(128).size,
                        cursor = store.cursor(),
                        lastSyncedAtEpochMs = nowEpochMs(),
                        error = null,
                    )
                if (store.pending(1).isNotEmpty()) scheduleCloudRetry(CLOUD_DEBOUNCE_MS)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleCloudFailure(error)
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }
    }

    private suspend fun accountTokenForSync(refresh: Boolean): String? =
        try {
            if (refresh) {
                accessTokens.refreshAccessTokenFor(cloud.origin)
            } else {
                accessTokens.validAccessTokenFor(cloud.origin)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // /auth/refresh failures are retryable account failures, not proof that
            // /account/playback is absent. Keep the local outbox and session intact.
            recordFailure(error)
            null
        }

    private suspend fun syncWithToken(
        accessToken: String,
        pullRemote: Boolean,
    ) {
        if (pullRemote) {
            if (!progressSyncEnabled.value) return
            val userId = cipher.currentUserId()
            pullAll(accessToken)
            userId?.let { synchronized(scheduleLock) { startupPullPendingUserIds.remove(it) } }
        }
        if (!progressSyncEnabled.value) return
        pushPending(accessToken)
        if (!progressSyncEnabled.value) return
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
                        deferPersist = true,
                    )
                if (applied.changedLocal && document.state.deviceId != store.deviceId) {
                    store.enqueueServerApply(
                        document = applied.document,
                        serverIds = serverApplier.targetServerIds(applied.document),
                    )
                }
            }
            // The page is written once, and before the cursor moves past it.
            store.flush()
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
            if (prepared.isEmpty()) throw PlaybackPushNoProgressException()
            val response =
                cloud.push(
                    accessToken,
                    PlaybackPushRequest(prepared.map { it.second }),
                )
            var madeProgress = false
            response.accepted.forEach { accepted ->
                val local =
                    prepared
                        .firstOrNull {
                            it.second.entity.entityKey == accepted.entityKey &&
                                it.second.entity.mutationId == accepted.mutationId &&
                                accepted.cursor > 0L
                        }?.first
                        ?: return@forEach
                store.markUploaded(
                    mediaKey = local.document.state.mediaKey,
                    aliases = local.document.state.aliases,
                    serverId = local.document.state.serverId,
                    entityKey = accepted.entityKey,
                    mutationId = accepted.mutationId,
                    cursor = accepted.cursor,
                    profileId = local.document.state.profileId,
                )
                madeProgress = true
            }
            response.conflicts.forEach { conflict ->
                val sent =
                    prepared.firstOrNull { it.second.entity.entityKey == conflict.entityKey }?.second
                        ?: return@forEach
                if (conflict.cursor <= 0L || conflict.cursor == sent.baseCursor) return@forEach
                val remote =
                    cipher.decrypt(conflict)
                        ?: throw PlaybackEntityDecryptException(conflict.entityKey)
                val applied =
                    store.applyRemote(
                        remote = remote,
                        entityKey = conflict.entityKey,
                        cursor = conflict.cursor,
                    )
                madeProgress = true
                if (applied.changedLocal && remote.state.deviceId != store.deviceId) {
                    store.enqueueServerApply(
                        document = applied.document,
                        serverIds = serverApplier.targetServerIds(applied.document),
                    )
                }
            }
            response.missing.forEach { missing ->
                val sent =
                    prepared.firstOrNull {
                        it.second.entity.entityKey == missing.entityKey &&
                            it.second.entity.mutationId == missing.mutationId &&
                            it.second.baseCursor == missing.baseCursor
                    } ?: return@forEach
                if (store.resetMissingRemoteCursor(sent.first, missing.entityKey, missing.baseCursor)) {
                    madeProgress = true
                }
            }
            if (!madeProgress) throw PlaybackPushNoProgressException()
        }
    }

    private suspend fun drainServerApplyQueue() {
        synchronized(scheduleLock) {
            serverRetryJob?.cancel()
            serverRetryJob = null
        }
        try {
            repeat(MAX_SERVER_APPLIES_PER_SYNC) {
                if (!progressSyncEnabled.value) return
                // A tap just claimed background priority: it releases at first output, a startup
                // error or its own deadline (see PlaybackLaunchTiming), all well under a minute.
                // Stepping aside costs this batch a retry, not the sync itself - see the
                // `finally` below, which reschedules regardless of how this loop exits.
                if (PlaybackLaunchTimings.anyHoldsBackgroundPriority()) return
                val task = store.pendingServerApplies(nowEpochMs(), limit = 1).firstOrNull() ?: return
                val serverId = task.readyServerIds(nowEpochMs()).firstOrNull()
                if (serverId == null || registryServerMissing(serverId)) {
                    serverId?.let { store.markServerApplySucceeded(task.id, it) }
                    return@repeat
                }
                serverApplier.cooldownUntil(serverId)?.let { until ->
                    store.deferServerApplyTarget(task.id, serverId, until)
                    return@repeat
                }
                if (!serverApplier.allowsBackgroundApply(serverId)) {
                    // The monitor already knows this server is refusing its session or offline;
                    // asking again would only collect another 401 or wait out a connect timeout.
                    // This check makes no request of its own, so a short recheck is free.
                    store.deferServerApplyTarget(task.id, serverId, nowEpochMs() + HEALTH_GATE_RECHECK_MS)
                    return@repeat
                }
                val result = serverApplier.apply(task.document, serverId)
                if (result.isSuccess) {
                    serverApplier.markReachable(serverId)
                    store.markServerApplySucceeded(task.id, serverId)
                    return@repeat
                }

                val failure = result.exceptionOrNull()
                if (failure is CancellationException) throw failure
                when (playbackServerApplyFailurePolicy(failure)) {
                    PlaybackServerApplyFailurePolicy.DropTarget -> {
                        // A missing item is permanent for this server/media mapping. Treat the target
                        // as consumed so it cannot sit at the head of the fan-out queue forever.
                        store.markServerApplySucceeded(task.id, serverId)
                        AppLog.warning(
                            category = "playback.sync",
                            event = "server_apply_target_dropped",
                            message = "Playback state target no longer exists on this media server",
                            throwable = failure,
                            attributes =
                                mapOf(
                                    "serverId" to serverId,
                                    "reason" to "not_found",
                                    "pendingCount" to store.serverApplyCount().toString(),
                                ),
                        )
                    }

                    PlaybackServerApplyFailurePolicy.CooldownServer -> {
                        // Authentication and WAF failures are server-wide, not item-specific. Repeating
                        // the same request every sync creates noise and can extend a WAF ban. Defer
                        // this target durably while allowing healthy targets to proceed.
                        val until = nowEpochMs() + PLAYBACK_SERVER_ACCESS_DENIED_COOLDOWN_MS
                        serverApplier.coolDownServer(serverId, until)
                        store.deferServerAppliesForServer(serverId, until)
                        AppLog.warning(
                            category = "playback.sync",
                            event = "server_apply_access_rejected_cooldown",
                            message = "Playback sync paused for a media server after access was rejected",
                            throwable = failure,
                            attributes =
                                mapOf(
                                    "serverId" to serverId,
                                    "cooldownMs" to PLAYBACK_SERVER_ACCESS_DENIED_COOLDOWN_MS.toString(),
                                    "pendingCount" to store.serverApplyCount().toString(),
                                ),
                        )
                    }

                    PlaybackServerApplyFailurePolicy.BackOffServer -> {
                        // An unreachable or failing server fails every task queued for it, and
                        // each attempt can hold a request for its whole timeout. Backing off only
                        // the task that failed let the next queued task try the same dead server
                        // straight away: one diagnostic showed a 30 s timeout every 30-90 s for
                        // half an hour while the per-task attempt counts kept restarting at 1.
                        val streak = serverApplier.markUnreachable(serverId)
                        val until = nowEpochMs() + playbackServerApplyBackoffMs(streak)
                        serverApplier.coolDownServer(serverId, until)
                        store.deferServerAppliesForServer(serverId, until)
                        AppLog.warning(
                            category = "playback.sync",
                            event = "server_apply_deferred",
                            message = "Cloud playback state remains queued for a media server",
                            throwable = failure,
                            attributes =
                                mapOf(
                                    "serverId" to serverId,
                                    "scope" to "server",
                                    "failureStreak" to streak.toString(),
                                    "backoffMs" to (until - nowEpochMs()).coerceAtLeast(0L).toString(),
                                    "pendingCount" to store.serverApplyCount().toString(),
                                ),
                        )
                    }

                    PlaybackServerApplyFailurePolicy.Retry -> {
                        val nextAttempt =
                            nowEpochMs() + playbackServerApplyBackoffMs(task.attemptCount + 1)
                        store.deferServerApply(task.id, nextAttempt)
                        AppLog.warning(
                            category = "playback.sync",
                            event = "server_apply_deferred",
                            message = "Cloud playback state remains queued for a media server",
                            throwable = failure,
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
        } finally {
            scheduleServerApplyRetry()
        }
    }

    private fun registryServerMissing(serverId: String): Boolean = serverApplier.serverMissing(serverId)

    private fun scheduleServerApplyRetry() {
        if (!progressSyncEnabled.value || !accessTokens.sessionAvailable.value) return
        val nextAttempt = store.nextServerApplyAtEpochMs() ?: return
        synchronized(scheduleLock) {
            serverRetryJob =
                scope.launch {
                    delay((nextAttempt - nowEpochMs()).coerceAtLeast(1_000L))
                    // Free the slot first: the drain below cancels whatever job still occupies it.
                    val self = coroutineContext[Job]
                    synchronized(scheduleLock) { if (serverRetryJob === self) serverRetryJob = null }
                    syncMutex.withLock {
                        if (progressSyncEnabled.value && accessTokens.sessionAvailable.value) {
                            drainServerApplyQueue()
                        }
                    }
                }
        }
    }

    private fun cancelScheduledJobs() =
        synchronized(scheduleLock) {
            debounceJob?.cancel()
            debounceJob = null
            urgentJob?.cancel()
            urgentJob = null
            retryJob?.cancel()
            retryJob = null
            serverRetryJob?.cancel()
            serverRetryJob = null
        }

    private fun scheduleCloudSync(immediate: Boolean) {
        if (!progressSyncEnabled.value) return
        if (cloudPlaybackEndpointUnavailable) return
        if (!accessTokens.sessionAvailable.value) return
        // Check-then-launch is one step: the player thread and a sync run may both get here.
        synchronized(scheduleLock) {
            if (immediate) {
                debounceJob?.cancel()
                debounceJob = null
                if (urgentJob?.isActive == true) return
                urgentJob =
                    scope.launch {
                        val now = nowEpochMs()
                        val lastAttempt = lastCloudAttemptAtEpochMs
                        val elapsed =
                            if (lastAttempt == Long.MIN_VALUE) {
                                Long.MAX_VALUE
                            } else {
                                (now - lastAttempt).coerceAtLeast(0L)
                            }
                        if (elapsed < MIN_URGENT_CLOUD_GAP_MS) {
                            delay(MIN_URGENT_CLOUD_GAP_MS - elapsed)
                        }
                        syncNow(pullRemote = false)
                    }
                return
            }
            if (debounceJob?.isActive == true || urgentJob?.isActive == true) return
            debounceJob =
                scope.launch {
                    delay(CLOUD_DEBOUNCE_MS)
                    syncNow(pullRemote = false)
                }
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
        synchronized(scheduleLock) {
            if (cloudPlaybackEndpointUnavailable) return
            retryJob?.cancel()
            retryJob = null
            cloudPlaybackEndpointUnavailable = true
            cloudFailureStreak = 0
            retryNotBeforeEpochMs = Long.MAX_VALUE
        }
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
        if (error is CancellationException) throw error
        val failureStreak =
            synchronized(scheduleLock) {
                cloudFailureStreak = (cloudFailureStreak + 1).coerceAtMost(MAX_CLOUD_FAILURE_STREAK)
                cloudFailureStreak
            }
        val backoffMs = playbackCloudRetryBackoffMs(failureStreak)
        retryNotBeforeEpochMs = nowEpochMs() + backoffMs
        val apiError = error as? AccountApiException
        AppLog.warning(
            category = "playback.sync",
            event = "cloud_sync_failed",
            message = "Cross-platform playback synchronization was deferred",
            throwable = error,
            attributes =
                buildMap {
                    put("failureStreak", failureStreak.toString())
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
        // Previously a timeout only wrote a deadline. No job retried it unless another
        // playback event happened, so the final Stop could remain unsynced indefinitely.
        synchronized(scheduleLock) {
            retryJob?.cancel()
            retryJob = null
        }
        scheduleCloudRetry(backoffMs)
    }

    private fun scheduleCloudRetry(delayMs: Long) {
        if (!progressSyncEnabled.value) return
        synchronized(scheduleLock) {
            if (retryJob?.isActive == true) return
            retryJob =
                scope.launch {
                    delay(delayMs.coerceAtLeast(0L))
                    // Free the slot before syncing: a failure inside that sync cancels the slot's
                    // occupant and schedules the next retry, which must not be this very job.
                    val self = coroutineContext[Job]
                    synchronized(scheduleLock) { if (retryJob === self) retryJob = null }
                    if (progressSyncEnabled.value && accessTokens.sessionAvailable.value) syncNow()
                }
        }
    }

    private fun markCloudSyncSucceeded() =
        synchronized(scheduleLock) {
            retryJob?.cancel()
            retryJob = null
            cloudFailureStreak = 0
            retryNotBeforeEpochMs = Long.MIN_VALUE
        }

    private companion object {
        const val CLOUD_DEBOUNCE_MS = 20_000L
        const val MIN_URGENT_CLOUD_GAP_MS = 5_000L
        const val MAX_CLOUD_FAILURE_STREAK = 6
        const val PULL_PAGE_SIZE = 100
        const val MAX_PULL_PAGES_PER_SYNC = 8
        const val PUSH_BATCH_SIZE = 8
        const val MAX_PUSH_ROUNDS = 2
        const val MAX_SERVER_APPLIES_PER_SYNC = 16
        const val COMPLETED_RATIO = 0.95

        /**
         * How soon a task skipped for [ServerHealthMonitor] is reconsidered. The check itself
         * makes no request, so this only has to be short enough that a server coming back
         * healthy is noticed promptly - it is not standing in for the monitor's own probe cadence.
         */
        const val HEALTH_GATE_RECHECK_MS = 60_000L
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

internal enum class PlaybackServerApplyFailurePolicy {
    DropTarget,
    CooldownServer,

    /** Transient, but server-wide: every queued task for the server waits out one backoff. */
    BackOffServer,
    Retry,
}

internal fun playbackServerApplyFailurePolicy(error: Throwable?): PlaybackServerApplyFailurePolicy =
    when ((error as? EmbyErrorException)?.error) {
        is EmbyError.AccessDenied,
        EmbyError.Unauthorized,
        -> PlaybackServerApplyFailurePolicy.CooldownServer
        // The item is gone from this server. Retrying cannot make it reappear, and the task stays
        // queued forever while every sync re-sends it and logs another deferral.
        EmbyError.NotFound -> PlaybackServerApplyFailurePolicy.DropTarget
        // A timeout, a dropped connection or a 5xx says nothing about this item: the next task
        // for the same server would fail the same way.
        EmbyError.Network,
        is EmbyError.Server,
        -> PlaybackServerApplyFailurePolicy.BackOffServer
        else -> PlaybackServerApplyFailurePolicy.Retry
    }

internal const val PLAYBACK_SERVER_ACCESS_DENIED_COOLDOWN_MS = 30 * 60_000L

/**
 * Whether a server-apply task should proceed now, given what [ServerHealthMonitor] already
 * knows about the target server. `null` covers both "no monitor was injected" and "the server
 * is no longer in the registry" - neither is a health verdict, so neither blocks the task.
 */
internal fun playbackSyncAllowsBackgroundApply(allowsBackgroundWork: Boolean?): Boolean = allowsBackgroundWork ?: true

private class PlaybackPushNoProgressException : IllegalStateException("云端未确认播放记录，本地记录已保留，稍后重试")

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

/**
 * Persists [EmbyCompatiblePlaybackStateApplier]'s per-server backoff deadline.
 *
 * The backoff (`coolDownServer`/`cooldownUntil`) used to live in a plain in-memory map, so a
 * process restart forgot a server was just marked unreachable and the next sync tried it again
 * immediately - one diagnostic showed a 30 s timeout every 30-90 s for half an hour. A read or
 * write failure is swallowed: this is a courtesy to a struggling server, never a source of
 * truth playback sync cannot run without.
 */
internal class PlaybackServerBackoffStore(
    private val settings: Settings,
) {
    @Serializable
    private data class Persisted(
        val untilEpochMsByServerId: Map<String, Long> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun load(): Map<String, Long> =
        runCatching {
            settings.getStringOrNull(KEY)?.let {
                json.decodeFromString(Persisted.serializer(), it).untilEpochMsByServerId
            }
        }.getOrNull().orEmpty()

    fun save(untilEpochMsByServerId: Map<String, Long>) {
        runCatching {
            settings.putString(KEY, json.encodeToString(Persisted.serializer(), Persisted(untilEpochMsByServerId)))
        }.onFailure { error ->
            AppLog.warning(
                category = "playback.sync",
                event = "server_backoff_persist_failed",
                message = "Per-server playback-sync backoff could not be saved",
                throwable = error,
            )
        }
    }

    private companion object {
        const val KEY = "playback_sync.server_backoff"
    }
}

/** Native server fan-out. The abstraction point is intentionally provider-neutral for Plex later. */
private class EmbyCompatiblePlaybackStateApplier(
    private val repo: EmbyRepository,
    private val registry: ServerRegistry,
    private val nowEpochMs: () -> Long,
    private val personal: PersonalLibraryRepository? = null,
    private val serverHealth: ServerHealthMonitor? = null,
    settings: Settings? = null,
) {
    private val backoffStore = settings?.let(::PlaybackServerBackoffStore)
    private val unavailableUntilByServerId =
        mutableMapOf<String, Long>().apply { backoffStore?.load()?.let(::putAll) }
    private val failureStreakByServerId = mutableMapOf<String, Int>()

    fun serverMissing(serverId: String): Boolean = registry.allDataForSync().servers.none { it.id == serverId }

    fun coolDownServer(
        serverId: String,
        untilEpochMs: Long,
    ) {
        unavailableUntilByServerId[serverId] = untilEpochMs
        backoffStore?.save(unavailableUntilByServerId)
    }

    /** One more consecutive transient failure for [serverId]; returns the new streak. */
    fun markUnreachable(serverId: String): Int {
        val streak = (failureStreakByServerId[serverId] ?: 0) + 1
        failureStreakByServerId[serverId] = streak
        return streak
    }

    fun markReachable(serverId: String) {
        failureStreakByServerId.remove(serverId)
    }

    /**
     * Whether [ServerHealthMonitor] - already consulted before a probe or a calendar fan-out -
     * also allows background playback-sync work against [serverId] right now. A server it has
     * marked as refusing the session or offline within its own backoff would only collect
     * another 401 or wait out a connect timeout here; this check makes no request of its own.
     */
    fun allowsBackgroundApply(serverId: String): Boolean =
        playbackSyncAllowsBackgroundApply(
            serverHealth?.let { health -> registry.serverById(serverId)?.let(health::allowsBackgroundWork) },
        )

    fun targetServerIds(document: PlaybackSyncDocument): List<String> {
        val state = document.state
        val keys = listOf(state.mediaKey) + state.aliases
        val hasPortableIdentity = keys.any { !it.startsWith("emby:", ignoreCase = true) }
        val candidates =
            if (hasPortableIdentity) {
                registry
                    .allDataForSync()
                    .servers
                    .map { it.id }
            } else {
                listOfNotNull(state.serverId?.takeUnless(::serverMissing))
            }
        // Keep the target queued even during cooldown; otherwise new progress is lost.
        return candidates.filter {
            personal == null ||
                personal.policyForProfile(state.profileId)?.allowsServer(it) == true
        }
    }

    fun cooldownUntil(serverId: String): Long? {
        val until = unavailableUntilByServerId[serverId] ?: return null
        if (nowEpochMs() < until) return until
        unavailableUntilByServerId.remove(serverId)
        backoffStore?.save(unavailableUntilByServerId)
        return null
    }

    suspend fun apply(
        document: PlaybackSyncDocument,
        serverId: String,
    ): Result<Unit> =
        runCatching {
            val state = document.state
            val owner = personal?.scopeToken
            check(personal == null || personal.activeProfileId == state.profileId) { "资料已切换，保留同步任务" }
            if (personal != null &&
                personal.policyForProfile(state.profileId)?.allowsServer(serverId) != true
            ) {
                return@runCatching
            }
            val server = registry.serverById(serverId) ?: return@runCatching
            val lookupKeys =
                playbackLookupKeys(
                    mediaKey = state.mediaKey,
                    aliases = state.aliases,
                    originServerId = state.serverId,
                    targetServerId = serverId,
                )
            val item =
                lookupKeys.firstNotNullOfOrNull { key ->
                    repo.findByMediaKey(server, key).fold(
                        onSuccess = { it },
                        onFailure = { failure ->
                            if ((failure as? EmbyErrorException)?.error == EmbyError.NotFound) {
                                null
                            } else {
                                throw failure
                            }
                        },
                    )
                } ?: return@runCatching
            check(personal == null || personal.scopeToken == owner) { "资料已切换，保留同步任务" }
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

/**
 * An Emby item id is scoped to one server. Sending `emby:<id>` to another server previously
 * produced repeated 500 responses before a portable TMDB/IMDb identity could be attempted.
 */
internal fun playbackLookupKeys(
    mediaKey: String,
    aliases: List<String>,
    originServerId: String?,
    targetServerId: String,
): List<String> {
    val keys = (listOf(mediaKey) + aliases).filter(String::isNotBlank).distinct()
    val isOrigin = originServerId == targetServerId
    return keys
        .filter { isOrigin || !it.startsWith("emby:", ignoreCase = true) }
        .sortedBy { key -> if (isOrigin && key.startsWith("emby:", ignoreCase = true)) 0 else 1 }
}
