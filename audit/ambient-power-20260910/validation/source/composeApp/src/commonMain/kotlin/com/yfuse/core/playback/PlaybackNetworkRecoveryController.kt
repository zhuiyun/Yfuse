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
    private var recoveryArmed = false
    private var resumePositionMs = 0L

    fun observe(
        networkClass: PlaybackNetworkClass,
        playbackRequested: Boolean,
        positionMs: Long,
        ended: Boolean,
    ): PlaybackNetworkRecoveryDecision {
        if (ended) {
            reset()
            return PlaybackNetworkRecoveryDecision()
        }
        when (networkClass) {
            PlaybackNetworkClass.Offline -> {
                if (!offline) {
                    offline = true
                    recoveryArmed = playbackRequested
                    resumePositionMs = positionMs.coerceAtLeast(0L)
                } else {
                    if (playbackRequested) {
                        recoveryArmed = true
                        resumePositionMs = positionMs.coerceAtLeast(0L)
                    } else {
                        recoveryArmed = false
                    }
                }
                return PlaybackNetworkRecoveryDecision()
            }

            PlaybackNetworkClass.Unknown -> return PlaybackNetworkRecoveryDecision()
            PlaybackNetworkClass.Metered,
            PlaybackNetworkClass.Unmetered,
            -> {
                if (!offline) return PlaybackNetworkRecoveryDecision()
                offline = false
                val shouldRetry = recoveryArmed
                recoveryArmed = false
                return PlaybackNetworkRecoveryDecision(
                    retry = shouldRetry,
                    resumePositionMs = resumePositionMs,
                )
            }
        }
    }

    fun reset() {
        offline = false
        recoveryArmed = false
        resumePositionMs = 0L
    }
}
