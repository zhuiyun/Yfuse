package com.yfuse.core.sync.playback

import com.russhwolf.settings.Settings
import com.yfuse.core.logging.AppLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random

class PlaybackSyncStore(
    private val settings: Settings,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    private val serializer = ListSerializer(StoredPlaybackDocument.serializer())
    private val serverApplySerializer = ListSerializer(PendingPlaybackServerApply.serializer())
    private val lock = Any()
    private var documents = loadDocuments().toMutableList()
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
        val keys = (document.state.aliases + document.state.mediaKey).filter(String::isNotBlank).toSet()
        val portableIdentity = keys.any { !it.startsWith("emby:", ignoreCase = true) }
        val retained =
            serverApplies.filterNot { queued ->
                val queuedKeys =
                    (queued.document.state.aliases + queued.document.state.mediaKey)
                        .filter(String::isNotBlank)
                        .toSet()
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
                .filter { it.remainingServerIds.isNotEmpty() && it.nextAttemptAtEpochMs <= nowEpochMs }
                .take(limit.coerceIn(1, MAX_SERVER_APPLY_BATCH))
        }

    fun serverApplyCount(): Int = synchronized(lock) { serverApplies.size }

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
                    attemptCount = 0,
                    nextAttemptAtEpochMs = 0L,
                )
        }
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
                .filter { it.serverId == serverId && !it.serverItemId.isNullOrBlank() }
                .sortedByDescending(PlaybackStateRecord::lastPlayedAtEpochMs)
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
            if (serverId.isBlank() || itemId.isBlank()) return@synchronized false
            val normalizedPosition = positionMs.coerceAtLeast(0L)
            if (!played && normalizedPosition == 0L) return@synchronized false
            val alreadyLocal =
                documents.any {
                    it.document.state.serverId == serverId &&
                        it.document.state.serverItemId == itemId
                }
            if (alreadyLocal) return@synchronized false
            val now = nowEpochMs()
            val state =
                PlaybackStateRecord(
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
            if (serverId.isBlank() || itemId.isBlank()) return@synchronized false
            val index =
                documents.indexOfLast {
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
            val previous = existing?.document
            val previousState = previous?.state
            val canonicalMediaKey = previousState?.mediaKey?.takeIf(String::isNotBlank) ?: mediaKey
            val revision = (previousState?.revision ?: 0L) + 1L
            val normalizedAliases =
                (previousState?.aliases.orEmpty() + aliases + mediaKey + previousState?.mediaKey.orEmpty())
                    .asSequence()
                    .filter(String::isNotBlank)
                    .filterNot { it == canonicalMediaKey }
                    .distinct()
                    .take(32)
                    .toList()
            val startsNewGeneration =
                trigger == PlaybackSyncTrigger.Started &&
                    positionMs.coerceAtLeast(0L) <= NEW_GENERATION_START_WINDOW_MS &&
                    (
                        previousState?.played == true ||
                            previousState?.mutationKind?.isManual == true
                    )
            val state =
                PlaybackStateRecord(
                    mediaKey = canonicalMediaKey,
                    aliases = normalizedAliases,
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    played = played,
                    lastPlayedAtEpochMs = now,
                    progressEpoch =
                        if (startsNewGeneration) {
                            nextProgressEpoch(previousState?.progressEpoch ?: 0L)
                        } else {
                            previousState?.progressEpoch ?: 0L
                        },
                    deviceId = deviceId,
                    sessionId = sessionId?.takeIf(String::isNotBlank),
                    serverId = serverId?.takeIf(String::isNotBlank),
                    serverItemId = serverItemId?.takeIf(String::isNotBlank),
                    revision = revision,
                    mutationKind = mutationKind,
                )
            val history = updateHistory(previous?.history.orEmpty(), state, trigger, now)
            val stored =
                StoredPlaybackDocument(
                    document =
                        PlaybackSyncDocument(
                            state = state,
                            preference = previous?.preference,
                            history = history,
                        ),
                    remoteCursors = existing?.remoteCursors.orEmpty(),
                    dirty = true,
                    mutationId = newId("mutation"),
                )
            replaceLocked(index, stored)
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
            val previous = existing?.document?.state
            val canonicalMediaKey = previous?.mediaKey?.takeIf(String::isNotBlank) ?: mediaKey
            val now = nowEpochMs()
            val state =
                PlaybackStateRecord(
                    mediaKey = canonicalMediaKey,
                    aliases =
                        (previous?.aliases.orEmpty() + aliases + mediaKey + previous?.mediaKey.orEmpty())
                            .filter(String::isNotBlank)
                            .filterNot { it == canonicalMediaKey }
                            .distinct()
                            .take(32),
                    positionMs = 0L,
                    durationMs = previous?.durationMs ?: 0L,
                    played = false,
                    lastPlayedAtEpochMs = now,
                    progressEpoch = nextProgressEpoch(previous?.progressEpoch ?: 0L),
                    deviceId = deviceId,
                    sessionId = null,
                    serverId = serverId ?: previous?.serverId,
                    serverItemId = serverItemId ?: previous?.serverItemId,
                    revision = (previous?.revision ?: 0L) + 1L,
                    // Keep the v1 enum closed for rolling-upgrade compatibility. The generation and
                    // zero position carry restart semantics for newer clients.
                    mutationKind = PlaybackMutationKind.AutoProgress,
                )
            val stored =
                StoredPlaybackDocument(
                    document =
                        PlaybackSyncDocument(
                            state = state,
                            preference = existing?.document?.preference,
                            history = existing?.document?.history.orEmpty(),
                        ),
                    remoteCursors = existing?.remoteCursors.orEmpty(),
                    dirty = true,
                    mutationId = newId("mutation"),
                )
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
            val previous = existing?.document?.state
            val canonicalMediaKey = previous?.mediaKey?.takeIf(String::isNotBlank) ?: mediaKey
            val now = nowEpochMs()
            val state =
                PlaybackStateRecord(
                    mediaKey = canonicalMediaKey,
                    aliases =
                        (previous?.aliases.orEmpty() + aliases + mediaKey + previous?.mediaKey.orEmpty())
                            .filter(String::isNotBlank)
                            .filterNot { it == canonicalMediaKey }
                            .distinct()
                            .take(32),
                    positionMs = if (watched) maxOf(previous?.positionMs ?: 0L, previous?.durationMs ?: 0L) else 0L,
                    durationMs = previous?.durationMs ?: 0L,
                    played = watched,
                    lastPlayedAtEpochMs = now,
                    progressEpoch = nextProgressEpoch(previous?.progressEpoch ?: 0L),
                    deviceId = deviceId,
                    sessionId = previous?.sessionId,
                    serverId = serverId ?: previous?.serverId,
                    serverItemId = serverItemId ?: previous?.serverItemId,
                    revision = (previous?.revision ?: 0L) + 1L,
                    mutationKind =
                        if (watched) PlaybackMutationKind.ManualWatched else PlaybackMutationKind.ManualUnwatched,
                )
            val stored =
                StoredPlaybackDocument(
                    document =
                        PlaybackSyncDocument(
                            state = state,
                            preference = existing?.document?.preference,
                            history = existing?.document?.history.orEmpty(),
                        ),
                    remoteCursors = existing?.remoteCursors.orEmpty(),
                    dirty = true,
                    mutationId = newId("mutation"),
                )
            replaceLocked(index, stored)
            stored
        }

    data class RemoteApplyResult(
        val document: PlaybackSyncDocument,
        val changedLocal: Boolean,
        val needsUpload: Boolean,
    )

    fun applyRemote(
        remote: PlaybackSyncDocument,
        entityKey: String,
        cursor: Long,
    ): RemoteApplyResult =
        synchronized(lock) {
            val index =
                findIndexLocked(
                    remote.state.mediaKey,
                    remote.state.aliases,
                    remote.state.serverId,
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
                replaceLocked(-1, stored)
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
                                    .filter(String::isNotBlank)
                                    .filterNot { it == localMediaKey }
                                    .distinct()
                                    .take(32),
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
            replaceLocked(index, stored)
            RemoteApplyResult(merged, changedLocal, needsUpload)
        }

    fun markUploaded(
        mediaKey: String,
        aliases: List<String>,
        serverId: String? = null,
        entityKey: String,
        mutationId: String,
        cursor: Long,
    ) = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases, serverId)
        val existing = documents.getOrNull(index) ?: return@synchronized
        if (existing.mutationId != mutationId) return@synchronized
        replaceLocked(
            index,
            existing.copy(
                remoteCursors = existing.remoteCursors + (entityKey to cursor),
                dirty = false,
            ),
        )
    }

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
    ): Int {
        val candidates = (aliases + mediaKey).filter(String::isNotBlank).toSet()
        if (candidates.isEmpty()) return -1
        val portableIdentity = candidates.any { !it.startsWith("emby:", ignoreCase = true) }
        if (!portableIdentity && serverId.isNullOrBlank()) return -1
        return documents.indexOfFirst { stored ->
            val state = stored.document.state
            val identityMatches =
                state.mediaKey in candidates || state.aliases.any(candidates::contains)
            identityMatches && (portableIdentity || state.serverId == serverId)
        }
    }

    private fun replaceLocked(
        index: Int,
        value: StoredPlaybackDocument,
    ) {
        if (index >= 0) documents[index] = value else documents += value
        documents =
            documents
                .sortedBy { it.document.state.lastPlayedAtEpochMs }
                .takeLast(MAX_LOCAL_DOCUMENTS)
                .toMutableList()
        persistLocked()
    }

    private fun persistLocked() {
        runCatching {
            settings.putString(KEY_DOCUMENTS, json.encodeToString(serializer, documents))
        }.onFailure { error ->
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
