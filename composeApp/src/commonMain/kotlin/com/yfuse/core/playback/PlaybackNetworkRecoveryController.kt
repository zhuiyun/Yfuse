package com.yfuse.core.playback

import com.yfuse.core.data.PlaybackNetworkClass

data class PlaybackNetworkRecoveryDecision(
    val retry: Boolean = false,
    val resumePositionMs: Long = 0L,
)

/**
 * Converts noisy connectivity callbacks into one resume attempt for each real outage.
 * Unknown is deliberately neutral: lack of platform evidence is not proof of either state.
 */
class PlaybackNetworkRecoveryController {
    private var offline = false

    fun observe(
        networkClass: PlaybackNetworkClass,
        playbackRequested: Boolean,
        positionMs: Long,
        ended: Boolean,
        remoteSource: Boolean = true,
        needsRecovery: Boolean = true,
        recoveryInProgress: Boolean = false,
    ): PlaybackNetworkRecoveryDecision {
        if (ended || !remoteSource || !playbackRequested) {
            reset()
            return PlaybackNetworkRecoveryDecision()
        }
        when (networkClass) {
            PlaybackNetworkClass.Offline -> {
                offline = true
                return PlaybackNetworkRecoveryDecision()
            }

            PlaybackNetworkClass.Unknown -> return PlaybackNetworkRecoveryDecision()
            PlaybackNetworkClass.Metered,
            PlaybackNetworkClass.Unmetered,
            -> {
                if (!offline) return PlaybackNetworkRecoveryDecision()
                offline = false
                val shouldRetry = needsRecovery && !recoveryInProgress
                return PlaybackNetworkRecoveryDecision(
                    retry = shouldRetry,
                    // Cached playback may have advanced during the outage. Never rewind it.
                    resumePositionMs = positionMs.coerceAtLeast(0L),
                )
            }
        }
    }

    fun reset() {
        offline = false
    }
}
