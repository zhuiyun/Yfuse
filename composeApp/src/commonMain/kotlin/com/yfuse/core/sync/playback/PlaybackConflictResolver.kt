package com.yfuse.core.sync.playback

import kotlin.math.abs

/** Deterministic conflict policy shared by every client platform. */
object PlaybackConflictResolver {
    private const val CONCURRENT_PROGRESS_WINDOW_MS = 10 * 60_000L

    fun merge(
        local: PlaybackSyncDocument,
        remote: PlaybackSyncDocument,
    ): PlaybackSyncDocument {
        val localState = local.state
        val remoteState = remote.state
        val winner = chooseState(localState, remoteState)
        val aliases =
            (localState.aliases + remoteState.aliases + localState.mediaKey + remoteState.mediaKey)
                .asSequence()
                .filter(String::isNotBlank)
                .distinct()
                .take(32)
                .toList()
        val preference = choosePreference(local.preference, remote.preference)
        val history = mergeHistory(local.history, remote.history)
        return PlaybackSyncDocument(
            state = winner.copy(aliases = aliases.filterNot { it == winner.mediaKey }),
            preference = preference,
            history = history,
        )
    }

    fun chooseState(
        local: PlaybackStateRecord,
        remote: PlaybackStateRecord,
    ): PlaybackStateRecord {
        if (local.mutationKind.isManual != remote.mutationKind.isManual) {
            return if (local.mutationKind.isManual) local else remote
        }
        if (local.mutationKind.isManual && remote.mutationKind.isManual) {
            return newest(local, remote)
        }

        val sameSession =
            local.sessionId != null &&
                remote.sessionId != null &&
                local.sessionId == remote.sessionId
        val nearInTime =
            abs(local.lastPlayedAtEpochMs - remote.lastPlayedAtEpochMs) <=
                CONCURRENT_PROGRESS_WINDOW_MS
        if (sameSession || nearInTime) {
            val newer = newest(local, remote)
            val furthest = if (local.positionMs >= remote.positionMs) local else remote
            return newer.copy(
                positionMs = maxOf(local.positionMs, remote.positionMs),
                durationMs = maxOf(local.durationMs, remote.durationMs),
                played = local.played || remote.played,
                mutationKind =
                    if (local.played || remote.played) {
                        PlaybackMutationKind.AutoFinished
                    } else {
                        furthest.mutationKind
                    },
            )
        }
        return newest(local, remote)
    }

    private fun newest(
        first: PlaybackStateRecord,
        second: PlaybackStateRecord,
    ): PlaybackStateRecord =
        compareValuesBy(
            first,
            second,
            PlaybackStateRecord::lastPlayedAtEpochMs,
            PlaybackStateRecord::revision,
            PlaybackStateRecord::deviceId,
        ).let { comparison -> if (comparison >= 0) first else second }

    private fun choosePreference(
        local: PlaybackTrackPreference?,
        remote: PlaybackTrackPreference?,
    ): PlaybackTrackPreference? =
        when {
            local == null -> remote
            remote == null -> local
            local.updatedAtEpochMs >= remote.updatedAtEpochMs -> local
            else -> remote
        }

    private fun mergeHistory(
        local: List<PlaybackHistoryEntry>,
        remote: List<PlaybackHistoryEntry>,
    ): List<PlaybackHistoryEntry> =
        (local + remote)
            .groupBy(PlaybackHistoryEntry::sessionId)
            .values
            .map { entries ->
                entries.maxWithOrNull(
                    compareBy<PlaybackHistoryEntry> { it.endedAtEpochMs ?: Long.MIN_VALUE }
                        .thenBy { it.endPositionMs },
                ) ?: entries.first()
            }.sortedBy(PlaybackHistoryEntry::startedAtEpochMs)
            .takeLast(MAX_HISTORY_PER_MEDIA)

    const val MAX_HISTORY_PER_MEDIA: Int = 24
}
