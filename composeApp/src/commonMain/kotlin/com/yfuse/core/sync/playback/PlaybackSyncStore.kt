package com.yfuse.core.sync.playback

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

class PlaybackSyncStore(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val personal: com.yfuse.core.personal.PersonalLibraryRepository? = null,
) {
    constructor(settings: Settings, nowEpochMs: () -> Long) : this(settings, nowEpochMs, null)

    private val activeProfileId: String get() =
        personal?.activeProfileId
            ?: com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE
    val scopeToken: String get() = personal?.scopeToken ?: "legacy"
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val serializer = ListSerializer(StoredPlaybackDocument.serializer())
    private val serverApplySerializer = ListSerializer(PendingPlaybackServerApply.serializer())
    private val lock = personal?.coordinationLock ?: Any()
    private var documents = loadDocuments().toMutableList()

    /** True while [documents] holds coalesced progress that settings have not received yet. */
    private var documentsUnsaved = false
    private var lastDocumentsPersistAtEpochMs = Long.MIN_VALUE
    private var batchingServerProgress = false
    private var serverProgressBatchChanged = false
    private var serverApplies = loadServerApplies().toMutableList()

    val deviceId: String =
        settings
            .getStringOrNull(KEY_DEVICE_ID)
            ?.takeIf(String::isNotBlank)
            ?: newId("device").also { settings.putString(KEY_DEVICE_ID, it) }

    /**
     * Binds the local cloud-sync partition to one Yfuse account. Re-entering the same account
     * keeps offline mutations; switching accounts removes the previous account's local history
     * and cursor before any ciphertext can be uploaded under the new vault key.
     *
     * The first account adopts anonymous local playback so a user can sign in after watching.
     * Returns true only when an existing account partition had to be reset.
     */
    fun bindAccount(userId: String): Boolean =
        synchronized(lock) {
            require(userId.isNotBlank())
            val previous = settings.getStringOrNull(KEY_ACCOUNT_USER_ID)?.takeIf(String::isNotBlank)
            if (previous == null) {
                settings.putString(KEY_ACCOUNT_USER_ID, userId)
                return@synchronized false
            }
            if (previous == userId) return@synchronized false

            documents.clear()
            documentsUnsaved = false
            serverApplies.clear()
            settings.remove(KEY_DOCUMENTS)
            settings.remove(KEY_SERVER_APPLIES)
            settings.putLong(KEY_CURSOR, 0L)
            settings.putString(KEY_ACCOUNT_USER_ID, userId)
            true
        }

    fun cursor(): Long = settings.getLong(KEY_CURSOR, 0L).coerceAtLeast(0L)

    fun updateCursor(value: Long) {
        val bounded = value.coerceAtLeast(0L)
        if (bounded > cursor()) settings.putLong(KEY_CURSOR, bounded)
    }

    fun enqueueServerApply(
        document: PlaybackSyncDocument,
        serverIds: List<String>,
    ) = synchronized(lock) {
        val targets = serverIds.filter(String::isNotBlank).distinct()
        if (targets.isEmpty()) return@synchronized
        // Replacing progress, or enqueuing a different title after a process restart, must
        // not forget a server-wide rejection already recorded in the durable queue.
        val now = nowEpochMs()
        val deferredUntilByServerId =
            targets
                .mapNotNull { serverId ->
                    serverApplies
                        .maxOfOrNull { it.deferredUntilByServerId[serverId] ?: 0L }
                        ?.takeIf { it > now }
                        ?.let { serverId to it }
                }.toMap()
        val keys = (document.state.aliases + document.state.mediaKey).filter(String::isNotBlank).toSet()
        val portableIdentity = keys.any { !it.startsWith("emby:", ignoreCase = true) }
        val retained =
            serverApplies.filterNot { queued ->
                val queuedKeys =
                    (queued.document.state.aliases + queued.document.state.mediaKey)
                        .filter(String::isNotBlank)
                        .toSet()
                queued.document.state.profileId == document.state.profileId &&
                    queuedKeys.any(keys::contains) &&
                    (portableIdentity || queued.document.state.serverId == document.state.serverId)
            }
        serverApplies =
            (
                retained +
                    PendingPlaybackServerApply(
                        id = newId("server-apply"),
                        document = document,
                        remainingServerIds = targets,
                        deferredUntilByServerId = deferredUntilByServerId,
                    )
            ).takeLast(MAX_SERVER_APPLIES).toMutableList()
        persistServerAppliesLocked()
    }

    fun pendingServerApplies(
        nowEpochMs: Long,
        limit: Int = 16,
    ): List<PendingPlaybackServerApply> =
        synchronized(lock) {
            serverApplies
                .filter {
                    it.document.state.profileId == activeProfileId &&
                        it.nextAttemptAtEpochMs <= nowEpochMs &&
                        it.readyServerIds(nowEpochMs).isNotEmpty()
                }.take(limit.coerceIn(1, MAX_SERVER_APPLY_BATCH))
        }

    fun serverApplyCount(): Int = synchronized(lock) { serverApplies.size }

    fun nextServerApplyAtEpochMs(): Long? =
        synchronized(lock) {
            serverApplies
                .filter { it.document.state.profileId == activeProfileId }
                .mapNotNull { task ->
                    task.remainingServerIds
                        .minOfOrNull { task.deferredUntilByServerId[it] ?: 0L }
                        ?.let { targetReadyAt -> maxOf(task.nextAttemptAtEpochMs, targetReadyAt) }
                }.minOrNull()
        }

    fun markServerApplySucceeded(
        taskId: String,
        serverId: String,
    ) = synchronized(lock) {
        val index = serverApplies.indexOfFirst { it.id == taskId }
        val existing = serverApplies.getOrNull(index) ?: return@synchronized
        val remaining = existing.remainingServerIds.filterNot { it == serverId }
        if (remaining.isEmpty()) {
            serverApplies.removeAt(index)
        } else {
            serverApplies[index] =
                existing.copy(
                    remainingServerIds = remaining,
                    deferredUntilByServerId = existing.deferredUntilByServerId - serverId,
                    attemptCount = 0,
                    nextAttemptAtEpochMs = 0L,
                )
        }
        persistServerAppliesLocked()
    }

    /** Keep rejected targets durable without holding healthy servers behind them. */
    fun deferServerApplyTarget(
        taskId: String,
        serverId: String,
        untilEpochMs: Long,
    ) = synchronized(lock) {
        val index = serverApplies.indexOfFirst { it.id == taskId }
        val existing = serverApplies.getOrNull(index) ?: return@synchronized
        if (serverId !in existing.remainingServerIds) return@synchronized
        serverApplies[index] =
            existing.copy(
                deferredUntilByServerId =
                    existing.deferredUntilByServerId + (serverId to untilEpochMs.coerceAtLeast(0L)),
            )
        persistServerAppliesLocked()
    }

    fun deferServerApply(
        taskId: String,
        nextAttemptAtEpochMs: Long,
    ) = synchronized(lock) {
        val index = serverApplies.indexOfFirst { it.id == taskId }
        val existing = serverApplies.getOrNull(index) ?: return@synchronized
        serverApplies[index] =
            existing.copy(
                attemptCount = (existing.attemptCount + 1).coerceAtMost(MAX_SERVER_APPLY_ATTEMPTS),
                nextAttemptAtEpochMs = nextAttemptAtEpochMs.coerceAtLeast(0L),
            )
        persistServerAppliesLocked()
    }

    /** Persist a server-wide denial in one write, including tasks beyond the current drain batch. */
    fun deferServerAppliesForServer(
        serverId: String,
        untilEpochMs: Long,
    ) = synchronized(lock) {
        val until = untilEpochMs.coerceAtLeast(0L)
        var changed = false
        serverApplies.indices.forEach { index ->
            val existing = serverApplies[index]
            if (
                serverId in existing.remainingServerIds &&
                (existing.deferredUntilByServerId[serverId] ?: 0L) < until
            ) {
                serverApplies[index] =
                    existing.copy(
                        deferredUntilByServerId = existing.deferredUntilByServerId + (serverId to until),
                    )
                changed = true
            }
        }
        if (changed) persistServerAppliesLocked()
    }

    fun find(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        serverId: String? = null,
    ): StoredPlaybackDocument? =
        synchronized(lock) {
            findIndexLocked(mediaKey, aliases, serverId).takeIf { it >= 0 }?.let(documents::get)
        }

    /** Device-local playback state for one concrete item on one media server. */
    fun stateForServerItem(
        serverId: String,
        itemId: String,
    ): PlaybackStateRecord? =
        synchronized(lock) {
            documents
                .asReversed()
                .firstOrNull {
                    it.document.state.profileId == activeProfileId &&
                        it.document.state.serverId == serverId &&
                        it.document.state.serverItemId == itemId
                }?.document
                ?.state
        }

    /** Snapshot used to build local-only resume and next-up shelves. */
    fun statesForServer(serverId: String): List<PlaybackStateRecord> =
        synchronized(lock) {
            documents
                .asSequence()
                .map { it.document.state }
                .filter {
                    it.profileId == activeProfileId && it.serverId == serverId && !it.serverItemId.isNullOrBlank()
                }.sortedByDescending(PlaybackStateRecord::lastPlayedAtEpochMs)
                .toList()
        }

    /**
     * Seeds one startup-only Emby/Jellyfin value when this device has never recorded the item.
     * Existing local state always wins, and imported values are clean so a pull cannot be echoed
     * back to Yfuse cloud as if it were a new local playback mutation.
     */
    fun seedServerProgressIfAbsent(
        serverId: String,
        itemId: String,
        positionMs: Long,
        played: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (activeProfileId != com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE) return@synchronized false
            if (serverId.isBlank() || itemId.isBlank()) return@synchronized false
            val normalizedPosition = positionMs.coerceAtLeast(0L)
            if (!played && normalizedPosition == 0L) return@synchronized false
            val alreadyLocal =
                documents.any {
                    it.document.state.profileId == activeProfileId &&
                        it.document.state.serverId == serverId &&
                        it.document.state.serverItemId == itemId
                }
            if (alreadyLocal) return@synchronized false
            val now = nowEpochMs()
            val state =
                PlaybackStateRecord(
                    profileId = activeProfileId,
                    mediaKey = "emby:$itemId",
                    positionMs = normalizedPosition,
                    durationMs = 0L,
                    played = played,
                    lastPlayedAtEpochMs = now,
                    deviceId = deviceId,
                    serverId = serverId,
                    serverItemId = itemId,
                    revision = 1L,
                    mutationKind =
                        if (played) {
                            PlaybackMutationKind.AutoFinished
                        } else {
                            PlaybackMutationKind.AutoProgress
                        },
                )
            replaceLocked(
                index = -1,
                value =
                    StoredPlaybackDocument(
                        document = PlaybackSyncDocument(state = state),
                        dirty = false,
                        mutationId = newId("server-seed"),
                    ),
            )
            true
        }

    /**
     * Takes the media server's progress for an item as the truth wherever this device has
     * nothing unsent of its own.
     *
     * A local record that is not dirty was either pulled from the server or already pushed
     * to it, so the server's newer value — an episode watched on the television — is the one
     * to show. A dirty record is a local playback the server has not received yet; it stays,
     * and the outbox delivers it. Imported values are clean, so a pull is never echoed back to
     * the cloud as a fresh local mutation. Returns true when anything changed.
     */
    fun absorbServerProgress(
        serverId: String,
        itemId: String,
        positionMs: Long,
        played: Boolean,
    ): Boolean =
        synchronized(lock) {
            if (activeProfileId != com.yfuse.core.personal.DEFAULT_PERSONAL_PROFILE) return@synchronized false
            if (serverId.isBlank() || itemId.isBlank()) return@synchronized false
            val index =
                documents.indexOfLast {
                    it.document.state.profileId == activeProfileId &&
                        it.document.state.serverId == serverId &&
                        it.document.state.serverItemId == itemId
                }
            if (index < 0) return@synchronized seedServerProgressIfAbsent(serverId, itemId, positionMs, played)
            val stored = documents[index]
            if (stored.dirty) return@synchronized false
            val normalizedPosition = positionMs.coerceAtLeast(0L)
            val current = stored.document.state
            if (current.positionMs == normalizedPosition && current.played == played) return@synchronized false
            val state =
                current.copy(
                    positionMs = normalizedPosition,
                    played = played,
                    lastPlayedAtEpochMs = nowEpochMs(),
                    revision = current.revision + 1L,
                    mutationKind =
                        if (played) {
                            PlaybackMutationKind.AutoFinished
                        } else {
                            PlaybackMutationKind.AutoProgress
                        },
                )
            replaceLocked(
                index = index,
                value =
                    stored.copy(
                        document = stored.document.copy(state = state),
                        dirty = false,
                        mutationId = newId("server-absorb"),
                    ),
            )
            true
        }

    data class ServerProgressInput(
        val itemId: String,
        val positionMs: Long,
        val played: Boolean,
    )

    /** One complete server pull sorts/serializes once, under the same account/profile lock. */
    fun absorbServerProgressBatch(
        serverId: String,
        progress: List<ServerProgressInput>,
        expectedScopeToken: String,
    ) = synchronized(lock) {
        if (scopeToken != expectedScopeToken) return@synchronized
        batchingServerProgress = true
        serverProgressBatchChanged = false
        try {
            progress.forEach { item ->
                absorbServerProgress(serverId, item.itemId, item.positionMs, item.played)
            }
        } finally {
            batchingServerProgress = false
            if (serverProgressBatchChanged) trimAndPersistDocumentsLocked()
            serverProgressBatchChanged = false
        }
    }

    fun pending(limit: Int = 64): List<StoredPlaybackDocument> =
        synchronized(lock) {
            documents.filter(StoredPlaybackDocument::dirty).take(limit.coerceIn(1, 128))
        }

    fun updatePlayback(
        mediaKey: String,
        aliases: List<String>,
        positionMs: Long,
        durationMs: Long,
        played: Boolean,
        sessionId: String?,
        serverId: String?,
        serverItemId: String?,
        mutationKind: PlaybackMutationKind,
        trigger: PlaybackSyncTrigger,
    ): StoredPlaybackDocument =
        synchronized(lock) {
            val now = nowEpochMs()
            val index = findIndexLocked(mediaKey, aliases, serverId)
            val existing = documents.getOrNull(index)
            val startsNewGeneration =
                trigger == PlaybackSyncTrigger.Started &&
                    positionMs.coerceAtLeast(0L) <= NEW_GENERATION_START_WINDOW_MS &&
                    (
                        existing?.document?.state?.played == true ||
                            existing
                                ?.document
                                ?.state
                                ?.mutationKind
                                ?.isManual == true
                    )
            val state =
                buildState(existing?.document?.state, mediaKey, aliases, now) { base ->
                    base.copy(
                        positionMs = positionMs.coerceAtLeast(0L),
                        durationMs = durationMs.coerceAtLeast(0L),
                        played = played,
                        progressEpoch =
                            if (startsNewGeneration) {
                                nextProgressEpoch(base.progressEpoch)
                            } else {
                                base.progressEpoch
                            },
                        sessionId = sessionId?.takeIf(String::isNotBlank),
                        // Playback names the server it runs on; it never inherits an earlier one.
                        serverId = serverId?.takeIf(String::isNotBlank),
                        serverItemId = serverItemId?.takeIf(String::isNotBlank),
                        mutationKind = mutationKind,
                    )
                }
            val history = updateHistory(existing?.document?.history.orEmpty(), state, trigger, now)
            val stored = locallyMutatedLocked(existing, state, history)
            // The ten-second tick of an existing record is the one write that may wait; a new
            // record, and every trigger that can be the last of a session, is written through.
            replaceLocked(index, stored, deferrable = trigger == PlaybackSyncTrigger.Periodic && index >= 0)
            stored
        }

    fun updatePreference(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        serverId: String? = null,
        transform: (PlaybackTrackPreference?) -> PlaybackTrackPreference,
    ): StoredPlaybackDocument? =
        synchronized(lock) {
            val index = findIndexLocked(mediaKey, aliases, serverId)
            val existing = documents.getOrNull(index) ?: return@synchronized null
            val preference = transform(existing.document.preference).copy(updatedAtEpochMs = nowEpochMs())
            val stored =
                existing.copy(
                    document = existing.document.copy(preference = preference),
                    dirty = true,
                    mutationId = newId("mutation"),
                )
            replaceLocked(index, stored)
            stored
        }

    fun markRestarted(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        serverId: String? = null,
        serverItemId: String? = null,
    ): StoredPlaybackDocument =
        synchronized(lock) {
            val index = findIndexLocked(mediaKey, aliases, serverId)
            val existing = documents.getOrNull(index)
            val state =
                buildState(existing?.document?.state, mediaKey, aliases, nowEpochMs()) { base ->
                    base.copy(
                        positionMs = 0L,
                        played = false,
                        progressEpoch = nextProgressEpoch(base.progressEpoch),
                        sessionId = null,
                        serverId = serverId ?: base.serverId,
                        serverItemId = serverItemId ?: base.serverItemId,
                        // Keep the v1 enum closed for rolling-upgrade compatibility. The generation and
                        // zero position carry restart semantics for newer clients.
                        mutationKind = PlaybackMutationKind.AutoProgress,
                    )
                }
            val stored = locallyMutatedLocked(existing, state, existing?.document?.history.orEmpty())
            replaceLocked(index, stored)
            stored
        }

    fun markManual(
        mediaKey: String,
        aliases: List<String> = emptyList(),
        watched: Boolean,
        serverId: String? = null,
        serverItemId: String? = null,
    ): StoredPlaybackDocument =
        synchronized(lock) {
            val index = findIndexLocked(mediaKey, aliases, serverId)
            val existing = documents.getOrNull(index)
            val state =
                buildState(existing?.document?.state, mediaKey, aliases, nowEpochMs()) { base ->
                    base.copy(
                        positionMs = if (watched) maxOf(base.positionMs, base.durationMs) else 0L,
                        played = watched,
                        progressEpoch = nextProgressEpoch(base.progressEpoch),
                        serverId = serverId ?: base.serverId,
                        serverItemId = serverItemId ?: base.serverItemId,
                        mutationKind =
                            if (watched) PlaybackMutationKind.ManualWatched else PlaybackMutationKind.ManualUnwatched,
                    )
                }
            val stored = locallyMutatedLocked(existing, state, existing?.document?.history.orEmpty())
            replaceLocked(index, stored)
            stored
        }

    data class RemoteApplyResult(
        val document: PlaybackSyncDocument,
        val changedLocal: Boolean,
        val needsUpload: Boolean,
    )

    /**
     * [deferPersist] is for a caller applying a whole pulled page: it must [flush] before it
     * advances the cursor, or a crash would skip the unsaved documents for good.
     */
    fun applyRemote(
        remote: PlaybackSyncDocument,
        entityKey: String,
        cursor: Long,
        deferPersist: Boolean = false,
    ): RemoteApplyResult =
        synchronized(lock) {
            val index =
                findIndexLocked(
                    remote.state.mediaKey,
                    remote.state.aliases,
                    remote.state.serverId,
                    remote.state.profileId,
                )
            val existing = documents.getOrNull(index)
            if (existing == null) {
                val stored =
                    StoredPlaybackDocument(
                        document = remote,
                        remoteCursors = mapOf(entityKey to cursor),
                        dirty = false,
                        mutationId = newId("remote"),
                    )
                replaceLocked(-1, stored, deferrable = deferPersist)
                return@synchronized RemoteApplyResult(remote, changedLocal = true, needsUpload = false)
            }
            val localMediaKey = existing.document.state.mediaKey
            val mergedRaw = PlaybackConflictResolver.merge(existing.document, remote)
            val merged =
                mergedRaw.copy(
                    state =
                        mergedRaw.state.copy(
                            mediaKey = localMediaKey,
                            aliases =
                                (mergedRaw.state.aliases + mergedRaw.state.mediaKey)
                                    .normalizedAliases(localMediaKey),
                        ),
                )
            val changedLocal = merged != existing.document
            val needsUpload = existing.dirty || (changedLocal && merged != remote)
            val stored =
                existing.copy(
                    document = merged,
                    remoteCursors = existing.remoteCursors + (entityKey to cursor),
                    dirty = needsUpload,
                    mutationId =
                        if (existing.dirty) {
                            existing.mutationId
                        } else if (needsUpload) {
                            newId("mutation")
                        } else {
                            existing.mutationId
                        },
                )
            replaceLocked(index, stored, deferrable = deferPersist)
            RemoteApplyResult(merged, changedLocal, needsUpload)
        }

    fun markUploaded(
        mediaKey: String,
        aliases: List<String>,
        serverId: String? = null,
        entityKey: String,
        mutationId: String,
        cursor: Long,
        profileId: String = activeProfileId,
    ) = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases, serverId, profileId)
        val existing = documents.getOrNull(index) ?: return@synchronized
        if (existing.mutationId != mutationId) return@synchronized
        // Losing an acknowledgement only re-sends a mutation the cloud already holds, and it
        // follows every debounced progress push - writing it through would undo the coalescing.
        replaceLocked(
            index,
            existing.copy(
                remoteCursors = existing.remoteCursors + (entityKey to cursor),
                dirty = false,
            ),
            deferrable = true,
        )
    }

    /**
     * Writes coalesced progress now. For the moments the process may go away without another
     * playback event: the app leaving the foreground, or a caller that needs the settings
     * value itself to be current.
     */
    fun flush() =
        synchronized(lock) {
            if (documentsUnsaved) persistLocked()
        }

    /**
     * The record a local mutation produces. Everything a mutation does not name is carried over
     * from [previous]; the identity, clock, device and revision are derived the same way for
     * all of them, and [overrides] states only what this particular mutation changes.
     */
    private fun buildState(
        previous: PlaybackStateRecord?,
        mediaKey: String,
        aliases: List<String>,
        now: Long,
        overrides: (base: PlaybackStateRecord) -> PlaybackStateRecord,
    ): PlaybackStateRecord {
        val canonicalMediaKey = previous?.mediaKey?.takeIf(String::isNotBlank) ?: mediaKey
        return PlaybackStateRecord(
            profileId = activeProfileId,
            mediaKey = canonicalMediaKey,
            aliases =
                (previous?.aliases.orEmpty() + aliases + mediaKey + previous?.mediaKey.orEmpty())
                    .normalizedAliases(canonicalMediaKey),
            positionMs = previous?.positionMs ?: 0L,
            durationMs = previous?.durationMs ?: 0L,
            played = previous?.played ?: false,
            lastPlayedAtEpochMs = now,
            progressEpoch = previous?.progressEpoch ?: 0L,
            deviceId = deviceId,
            sessionId = previous?.sessionId,
            serverId = previous?.serverId,
            serverItemId = previous?.serverItemId,
            revision = (previous?.revision ?: 0L) + 1L,
        ).let(overrides)
    }

    /** Every other name a title is known by, without blanks, repeats or its canonical key. */
    private fun List<String>.normalizedAliases(canonicalMediaKey: String): List<String> =
        asSequence()
            .filter(String::isNotBlank)
            .filterNot { it == canonicalMediaKey }
            .distinct()
            .take(MAX_ALIASES)
            .toList()

    private fun locallyMutatedLocked(
        existing: StoredPlaybackDocument?,
        state: PlaybackStateRecord,
        history: List<PlaybackHistoryEntry>,
    ): StoredPlaybackDocument =
        StoredPlaybackDocument(
            document =
                PlaybackSyncDocument(
                    state = state,
                    preference = existing?.document?.preference,
                    history = history,
                ),
            remoteCursors = existing?.remoteCursors.orEmpty(),
            dirty = true,
            mutationId = newId("mutation"),
        )

    private fun updateHistory(
        current: List<PlaybackHistoryEntry>,
        state: PlaybackStateRecord,
        trigger: PlaybackSyncTrigger,
        now: Long,
    ): List<PlaybackHistoryEntry> {
        val session = state.sessionId ?: return current
        val index = current.indexOfLast { it.sessionId == session }
        val existing = current.getOrNull(index)
        val updated =
            when {
                existing == null ->
                    PlaybackHistoryEntry(
                        sessionId = session,
                        startedAtEpochMs = now,
                        endedAtEpochMs = now.takeIf { trigger in TERMINAL_TRIGGERS },
                        startPositionMs = state.positionMs,
                        endPositionMs = state.positionMs,
                        deviceId = state.deviceId,
                        serverId = state.serverId,
                    )
                else ->
                    existing.copy(
                        endedAtEpochMs = now.takeIf { trigger in TERMINAL_TRIGGERS } ?: existing.endedAtEpochMs,
                        endPositionMs = maxOf(existing.endPositionMs, state.positionMs),
                    )
            }
        return (current.filterNot { it.sessionId == session } + updated)
            .sortedBy(PlaybackHistoryEntry::startedAtEpochMs)
            .takeLast(PlaybackConflictResolver.MAX_HISTORY_PER_MEDIA)
    }

    private fun findIndexLocked(
        mediaKey: String,
        aliases: List<String>,
        serverId: String?,
        profileId: String = activeProfileId,
    ): Int {
        val candidates = (aliases + mediaKey).filter(String::isNotBlank).toSet()
        if (candidates.isEmpty()) return -1
        val portableIdentity = candidates.any { !it.startsWith("emby:", ignoreCase = true) }
        if (!portableIdentity && serverId.isNullOrBlank()) return -1
        return documents.indexOfFirst { stored ->
            val state = stored.document.state
            val identityMatches =
                state.mediaKey in candidates || state.aliases.any(candidates::contains)
            state.profileId == profileId && identityMatches && (portableIdentity || state.serverId == serverId)
        }
    }

    /**
     * [deferrable] marks a write whose loss costs at most [DEFERRED_PERSIST_MAX_AGE_MS] of
     * progress. Persisting is a full re-serialization of up to [MAX_LOCAL_DOCUMENTS] documents
     * - hundreds of KB for a long-time user - and used to run on every ten-second tick. Such
     * writes now only mark the list unsaved; the next written-through mutation (pause, seek,
     * stop, background, completion, anything manual or remote), [flush], or the age limit
     * carries them to disk. In-memory reads are unaffected either way.
     */
    private fun replaceLocked(
        index: Int,
        value: StoredPlaybackDocument,
        deferrable: Boolean = false,
    ) {
        if (index >= 0) documents[index] = value else documents += value
        if (batchingServerProgress) {
            serverProgressBatchChanged = true
            return
        }
        trimDocumentsLocked()
        if (deferrable && !deferredPersistOverdueLocked()) {
            documentsUnsaved = true
            return
        }
        persistLocked()
    }

    private fun deferredPersistOverdueLocked(): Boolean {
        val last = lastDocumentsPersistAtEpochMs
        if (last == Long.MIN_VALUE) return true
        // A clock that moved backwards counts as overdue rather than postponing the write.
        return nowEpochMs() - last !in 0L until DEFERRED_PERSIST_MAX_AGE_MS
    }

    private fun trimAndPersistDocumentsLocked() {
        trimDocumentsLocked()
        persistLocked()
    }

    private fun trimDocumentsLocked() {
        documents =
            documents
                .sortedBy { it.document.state.lastPlayedAtEpochMs }
                .takeLast(MAX_LOCAL_DOCUMENTS)
                .toMutableList()
    }

    private fun persistLocked() {
        runCatching {
            settings.putString(KEY_DOCUMENTS, json.encodeToString(serializer, documents))
            documentsUnsaved = false
            lastDocumentsPersistAtEpochMs = nowEpochMs()
        }.onFailure { error ->
            // Still unsaved: the next write-through or flush tries again.
            documentsUnsaved = true
            AppLog.error(
                category = "playback.sync",
                event = "local_persist_failed",
                message = "Cross-platform playback state could not be persisted",
                throwable = error,
            )
        }
    }

    private fun persistServerAppliesLocked() {
        runCatching {
            if (serverApplies.isEmpty()) {
                settings.remove(KEY_SERVER_APPLIES)
            } else {
                settings.putString(
                    KEY_SERVER_APPLIES,
                    json.encodeToString(serverApplySerializer, serverApplies),
                )
            }
        }.onFailure { error ->
            AppLog.error(
                category = "playback.sync",
                event = "server_apply_persist_failed",
                message = "Cloud-to-server playback operations could not be persisted",
                throwable = error,
            )
        }
    }

    private fun loadDocuments(): List<StoredPlaybackDocument> {
        val raw = settings.getStringOrNull(KEY_DOCUMENTS) ?: return emptyList()
        if (raw.encodeToByteArray().size > MAX_STORED_BYTES) {
            discardInvalidDocuments("oversized")
            return emptyList()
        }
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { discardInvalidDocuments("invalid") }
            .getOrDefault(emptyList())
            .takeLast(MAX_LOCAL_DOCUMENTS)
    }

    private fun discardInvalidDocuments(reason: String) {
        settings.remove(KEY_DOCUMENTS)
        settings.remove(KEY_SERVER_APPLIES)
        settings.putLong(KEY_CURSOR, 0L)
        AppLog.warning(
            category = "playback.sync",
            event = "local_documents_discarded",
            message = "Invalid local playback state was discarded and cloud replay was requested",
            attributes = mapOf("reason" to reason),
        )
    }

    private fun loadServerApplies(): List<PendingPlaybackServerApply> {
        val raw = settings.getStringOrNull(KEY_SERVER_APPLIES) ?: return emptyList()
        if (raw.encodeToByteArray().size > MAX_SERVER_APPLY_STORED_BYTES) {
            settings.remove(KEY_SERVER_APPLIES)
            return emptyList()
        }
        return runCatching { json.decodeFromString(serverApplySerializer, raw) }
            .onFailure { settings.remove(KEY_SERVER_APPLIES) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.remainingServerIds.isNotEmpty() }
            .takeLast(MAX_SERVER_APPLIES)
    }

    private fun nextProgressEpoch(current: Long): Long =
        if (current == Long.MAX_VALUE) Long.MAX_VALUE else current.coerceAtLeast(0L) + 1L

    private fun newId(prefix: String): String =
        "$prefix-${Random.nextLong().toULong().toString(16)}-${nowEpochMs().toString(16)}"

    private companion object {
        const val KEY_DOCUMENTS = "playback.cross_platform.documents.v1"
        const val KEY_CURSOR = "playback.cross_platform.cursor.v1"
        const val KEY_DEVICE_ID = "playback.cross_platform.device.v1"
        const val KEY_ACCOUNT_USER_ID = "playback.cross_platform.account_user.v1"
        const val KEY_SERVER_APPLIES = "playback.cross_platform.server_applies.v1"
        const val MAX_LOCAL_DOCUMENTS = 512
        const val MAX_ALIASES = 32

        /** The most playback progress a process death can cost; see [replaceLocked]. */
        const val DEFERRED_PERSIST_MAX_AGE_MS = 60_000L
        const val MAX_STORED_BYTES = 4 * 1024 * 1024
        const val MAX_SERVER_APPLIES = 512
        const val MAX_SERVER_APPLY_BATCH = 32
        const val MAX_SERVER_APPLY_ATTEMPTS = 20
        const val MAX_SERVER_APPLY_STORED_BYTES = 4 * 1024 * 1024
        const val NEW_GENERATION_START_WINDOW_MS = 5_000L
        val TERMINAL_TRIGGERS =
            setOf(
                PlaybackSyncTrigger.Stop,
                PlaybackSyncTrigger.Background,
                PlaybackSyncTrigger.Completed,
            )
    }
}
