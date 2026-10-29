package com.yfuse.core.sync.playback

/** Deterministic conflict policy shared by every client platform. */
object PlaybackConflictResolver {
    fun merge(
        local: PlaybackSyncDocument,
        remote: PlaybackSyncDocument,
    ): PlaybackSyncDocument {
        val localState = local.state
        val remoteState = remote.state
        require(localState.profileId == remoteState.profileId) { "Playback profiles must not be merged" }
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
            state =
                winner.copy(
                    aliases = aliases.filterNot { it == winner.mediaKey },
                    serverItems = mergePlaybackBindings(localState.sourceBindings(), remoteState.sourceBindings()),
                ),
            preference = preference,
            history = history,
        )
    }

    fun chooseState(
        local: PlaybackStateRecord,
        remote: PlaybackStateRecord,
    ): PlaybackStateRecord {
        // An explicit restart/reset creates a new playback generation. Nothing arriving late from
        // an older generation may resurrect its larger position or watched state.
        if (local.progressEpoch != remote.progressEpoch) {
            return if (local.progressEpoch > remote.progressEpoch) local else remote
        }

        if (local.mutationKind.isManual != remote.mutationKind.isManual) {
            return if (local.mutationKind.isManual) local else remote
        }
        if (local.mutationKind.isManual && remote.mutationKind.isManual) {
            return newest(local, remote)
        }

        // A newer stop/seek is authoritative, including a deliberately smaller position.
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
                    compareBy<PlaybackHistoryEntry> {
                        it.updatedAtEpochMs.takeIf { updated -> updated > 0L }
                            ?: it.endedAtEpochMs ?: it.startedAtEpochMs
                    }.thenBy { it.endPositionMs },
                ) ?: entries.first()
            }.sortedBy(PlaybackHistoryEntry::startedAtEpochMs)
            .takeLast(MAX_HISTORY_PER_MEDIA)

    const val MAX_HISTORY_PER_MEDIA: Int = 24
}
