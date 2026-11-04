package com.yfuse.feature.player

/** Immutable session memory for the last trustworthy playback timeline. */
internal data class PlaybackTimelineMemory(
    val identity: PlaybackTimelineIdentity? = null,
    val lastValid: PlaybackTimelineSample? = null,
    val owner: Any? = null,
    val seekRevision: Long = 0L,
)

internal data class PlaybackTimelineResolution(
    val state: PlaybackState,
    val memory: PlaybackTimelineMemory,
)

internal data class PlaybackTimelineSample(
    val positionMs: Long,
    val durationMs: Long,
    val bufferedPositionMs: Long,
)

/**
 * Keeps the last trustworthy timeline while the active backend is rebuilding or buffering.
 *
 * A duration of zero (or Media3's negative TIME_UNSET sentinel) means "not known yet", not
 * "the media is zero milliseconds long". Memory is owned by the player session rather than an
 * individual backend, so a source or engine handover cannot flash the progress bar to 0/100%.
 */
internal fun stabilizePlaybackTimeline(
    memory: PlaybackTimelineMemory,
    media: PlaybackTimelineIdentity?,
    reported: PlaybackState,
    owner: Any? = null,
    seekRevision: Long = 0L,
): PlaybackTimelineResolution {
    // Revisions are local to a player. A replacement starting at revision zero is a handover,
    // while a new revision from the same player is an intentional timeline discontinuity.
    val explicitSeek = media == memory.identity && owner === memory.owner && seekRevision != memory.seekRevision
    val activeMemory =
        if (media == memory.identity) {
            memory
        } else {
            PlaybackTimelineMemory(identity = media)
        }.copy(owner = owner, seekRevision = seekRevision)
    val previous = activeMemory.lastValid
    if (reported.durationMs <= 0L) {
        if (explicitSeek && previous != null) {
            val sought = reported.copy(durationMs = previous.durationMs)
            return PlaybackTimelineResolution(
                sought,
                activeMemory.copy(
                    lastValid = PlaybackTimelineSample(sought.positionMs, sought.durationMs, sought.bufferedPositionMs),
                ),
            )
        }
        val stabilized =
            previous?.let { valid ->
                reported.copy(
                    positionMs = valid.positionMs,
                    durationMs = valid.durationMs,
                    bufferedPositionMs = valid.bufferedPositionMs,
                )
            } ?: reported.copy(durationMs = 0L)
        return PlaybackTimelineResolution(stabilized, activeMemory)
    }

    val stabilized =
        if (!explicitSeek && reported.buffering && previous != null && reported.positionMs < previous.positionMs) {
            reported.copy(
                positionMs = previous.positionMs,
                bufferedPositionMs =
                    maxOf(reported.bufferedPositionMs, previous.bufferedPositionMs),
            )
        } else {
            reported
        }
    return PlaybackTimelineResolution(
        state = stabilized,
        memory =
            activeMemory.copy(
                lastValid =
                    PlaybackTimelineSample(
                        positionMs = stabilized.positionMs,
                        durationMs = stabilized.durationMs,
                        bufferedPositionMs = stabilized.bufferedPositionMs,
                    ),
            ),
    )
}

/** Queue identity deliberately excludes the selected file, server fallback, and player engine. */
internal data class PlaybackTimelineIdentity(
    val queueIndex: Int,
    val serverId: String?,
    val itemId: String,
)
