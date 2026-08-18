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
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(StoredPlaybackDocument.serializer())
    private val lock = Any()
    private var documents = loadDocuments().toMutableList()

    val deviceId: String =
        settings.getStringOrNull(KEY_DEVICE_ID)
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
    fun bindAccount(userId: String): Boolean = synchronized(lock) {
        require(userId.isNotBlank())
        val previous = settings.getStringOrNull(KEY_ACCOUNT_USER_ID)?.takeIf(String::isNotBlank)
        if (previous == null) {
            settings.putString(KEY_ACCOUNT_USER_ID, userId)
            return@synchronized false
        }
        if (previous == userId) return@synchronized false

        documents.clear()
        settings.remove(KEY_DOCUMENTS)
        settings.putLong(KEY_CURSOR, 0L)
        settings.putString(KEY_ACCOUNT_USER_ID, userId)
        true
    }

    fun cursor(): Long = settings.getLong(KEY_CURSOR, 0L).coerceAtLeast(0L)

    fun updateCursor(value: Long) {
        val bounded = value.coerceAtLeast(0L)
        if (bounded > cursor()) settings.putLong(KEY_CURSOR, bounded)
    }

    fun find(
        mediaKey: String,
        aliases: List<String> = emptyList(),
    ): StoredPlaybackDocument? = synchronized(lock) {
        findIndexLocked(mediaKey, aliases).takeIf { it >= 0 }?.let(documents::get)
    }

    fun pending(limit: Int = 64): List<StoredPlaybackDocument> = synchronized(lock) {
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
    ): StoredPlaybackDocument = synchronized(lock) {
        val now = nowEpochMs()
        val index = findIndexLocked(mediaKey, aliases)
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
                    if (startsNewGeneration) nextProgressEpoch(previousState?.progressEpoch ?: 0L)
                    else previousState?.progressEpoch ?: 0L,
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
        transform: (PlaybackTrackPreference?) -> PlaybackTrackPreference,
    ): StoredPlaybackDocument? = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases)
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
    ): StoredPlaybackDocument = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases)
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
                mutationKind = PlaybackMutationKind.ManualRestart,
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
    ): StoredPlaybackDocument = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases)
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
    ): RemoteApplyResult = synchronized(lock) {
        val index = findIndexLocked(remote.state.mediaKey, remote.state.aliases)
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
                    if (existing.dirty) existing.mutationId
                    else if (needsUpload) newId("mutation")
                    else existing.mutationId,
            )
        replaceLocked(index, stored)
        RemoteApplyResult(merged, changedLocal, needsUpload)
    }

    fun markUploaded(
        mediaKey: String,
        aliases: List<String>,
        entityKey: String,
        mutationId: String,
        cursor: Long,
    ) = synchronized(lock) {
        val index = findIndexLocked(mediaKey, aliases)
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
    ): Int {
        val candidates = (aliases + mediaKey).filter(String::isNotBlank).toSet()
        if (candidates.isEmpty()) return -1
        return documents.indexOfFirst { stored ->
            val state = stored.document.state
            state.mediaKey in candidates || state.aliases.any(candidates::contains)
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

    private fun loadDocuments(): List<StoredPlaybackDocument> {
        val raw = settings.getStringOrNull(KEY_DOCUMENTS) ?: return emptyList()
        if (raw.encodeToByteArray().size > MAX_STORED_BYTES) {
            settings.remove(KEY_DOCUMENTS)
            return emptyList()
        }
        return runCatching { json.decodeFromString(serializer, raw) }
            .onFailure { settings.remove(KEY_DOCUMENTS) }
            .getOrDefault(emptyList())
            .takeLast(MAX_LOCAL_DOCUMENTS)
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
        const val MAX_LOCAL_DOCUMENTS = 512
        const val MAX_STORED_BYTES = 4 * 1024 * 1024
        const val NEW_GENERATION_START_WINDOW_MS = 5_000L
        val TERMINAL_TRIGGERS =
            setOf(
                PlaybackSyncTrigger.Stop,
                PlaybackSyncTrigger.Background,
                PlaybackSyncTrigger.Completed,
            )
    }
}
