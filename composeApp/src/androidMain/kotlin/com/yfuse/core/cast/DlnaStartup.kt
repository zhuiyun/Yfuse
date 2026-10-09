package com.yfuse.core.cast

/**
 * A renderer that accepted SetAVTransportURI and Play has only promised to try. Some answer PLAYING
 * at once and keep answering it while their position stays at 0:00 because they never opened the
 * file, so a load counts as started only once the renderer shows it is really playing.
 */
internal enum class DlnaStartFailure(
    val diagnosticName: String,
) {
    /** The renderer fell back to STOPPED or NO_MEDIA_PRESENT without ever playing. */
    Stopped("stopped"),

    /** The media went through this phone and the renderer never asked for it. */
    NeverRequested("never_requested"),

    /** The renderer read some of the media through this phone, then stopped reading. */
    Abandoned("abandoned"),

    /** None of the above, but its position did not move within the start budget. */
    NoProgress("no_progress"),
}

/** What the phone relay saw of one load, when the media goes through it. */
internal data class DlnaRelayActivity(
    val requests: Int,
    val bytesServed: Long,
    /** Monotonic time of the renderer's last request or read; null before its first request. */
    val lastActivityAtMs: Long?,
    /** Reads the server refused or broke off; a renderer closing its own read is not counted. */
    val upstreamFailures: Int = 0,
)

internal sealed interface DlnaStartVerdict {
    data object Waiting : DlnaStartVerdict

    /** [positionMs] is the receiver's clock once it has moved; null when it has none that does. */
    data class Started(
        val positionMs: Long?,
    ) : DlnaStartVerdict

    data class Failed(
        val failure: DlnaStartFailure,
    ) : DlnaStartVerdict
}

internal data class DlnaStartPolicy(
    /** How far the clock has to move while PLAYING to count as playback. */
    val progressMs: Long = 1_500L,
    /** A renderer without a clock (NOT_IMPLEMENTED) is taken at its word after this long PLAYING. */
    val clocklessConfirmMs: Long = 2_000L,
    /** Relayed bytes and PLAYING time that show a renderer with a frozen clock is still playing. */
    val streamingBytes: Long = 8L * 1024L * 1024L,
    val streamingConfirmMs: Long = 10_000L,
    /** A relayed load the renderer has not requested by now is not going to be. */
    val firstRequestMs: Long = 20_000L,
    /** A relayed load the renderer stopped reading this long ago, without playing, was given up. */
    val abandonedMs: Long = 25_000L,
    /** The overall budget: slow servers make a large file take a while to open, but not this long. */
    val timeoutMs: Long = 75_000L,
)

/**
 * Watches the first polls of one DLNA load. Time spent PAUSED does not count: the viewer may pause
 * before the picture arrives, and a paused renderer neither moves its clock nor reads.
 */
internal class DlnaStartMonitor(
    private val startedAtMs: Long,
    private val policy: DlnaStartPolicy = DlnaStartPolicy(),
) {
    private var baselinePositionMs: Long? = null
    private var clocklessPlayingSinceMs: Long? = null
    private var playingSinceMs: Long? = null
    private var consecutiveStopped = 0
    private var pausedMs = 0L
    private var lastObservationAtMs: Long? = null
    private var lastWasPaused = false
    private var resumedAtMs: Long? = null

    /** A seek moves the clock without playback; progress is measured again from where it lands. */
    fun rebase() {
        baselinePositionMs = null
    }

    fun observe(
        status: CastPlaybackStatus,
        positionMs: Long?,
        nowMs: Long,
        relay: DlnaRelayActivity? = null,
    ): DlnaStartVerdict {
        val previousAtMs = lastObservationAtMs
        if (lastWasPaused && previousAtMs != null) pausedMs += (nowMs - previousAtMs).coerceAtLeast(0L)
        val paused = status == CastPlaybackStatus.Paused
        if (lastWasPaused && !paused) resumedAtMs = nowMs
        lastWasPaused = paused
        lastObservationAtMs = nowMs
        if (paused) {
            playingSinceMs = null
            clocklessPlayingSinceMs = null
            return DlnaStartVerdict.Waiting
        }

        if (status == CastPlaybackStatus.Ended) {
            consecutiveStopped++
            // One STOPPED can be a renderer passing through on its way to TRANSITIONING.
            if (consecutiveStopped >= STOPPED_CONFIRMATIONS) return DlnaStartVerdict.Failed(DlnaStartFailure.Stopped)
        } else {
            consecutiveStopped = 0
        }

        if (status == CastPlaybackStatus.Playing) {
            val playingSince = playingSinceMs ?: nowMs.also { playingSinceMs = it }
            if (positionMs != null) {
                clocklessPlayingSinceMs = null
                val baseline = baselinePositionMs
                when {
                    baseline == null || positionMs < baseline -> baselinePositionMs = positionMs
                    positionMs - baseline >= policy.progressMs -> return DlnaStartVerdict.Started(positionMs)
                }
            } else {
                val since = clocklessPlayingSinceMs ?: nowMs.also { clocklessPlayingSinceMs = it }
                if (nowMs - since >= policy.clocklessConfirmMs) return DlnaStartVerdict.Started(null)
            }
            val lastActivity = relay?.lastActivityAtMs
            if (
                relay != null &&
                lastActivity != null &&
                relay.bytesServed >= policy.streamingBytes &&
                nowMs - playingSince >= policy.streamingConfirmMs &&
                nowMs - lastActivity < policy.abandonedMs
            ) {
                return DlnaStartVerdict.Started(null)
            }
        } else {
            playingSinceMs = null
            clocklessPlayingSinceMs = null
        }

        val activeMs = nowMs - startedAtMs - pausedMs
        if (relay != null) {
            if (relay.requests == 0 && activeMs >= policy.firstRequestMs) {
                return DlnaStartVerdict.Failed(DlnaStartFailure.NeverRequested)
            }
            val lastActivity = relay.lastActivityAtMs
            if (lastActivity != null) {
                // A pause stops the reading too; the quiet spell only counts from when it ended.
                val quietSince = maxOf(lastActivity, resumedAtMs ?: lastActivity)
                if (nowMs - quietSince >= policy.abandonedMs) {
                    return DlnaStartVerdict.Failed(DlnaStartFailure.Abandoned)
                }
            }
        }
        if (activeMs >= policy.timeoutMs) return DlnaStartVerdict.Failed(DlnaStartFailure.NoProgress)
        return DlnaStartVerdict.Waiting
    }
}

private const val STOPPED_CONFIRMATIONS = 2

/**
 * A start confirmed without a moving clock (none reported, or one stuck while the relay showed the
 * renderer reading) leaves its position unused. This trusts the clock from the moment it moves.
 */
internal class DlnaClockWatch(
    private val progressMs: Long = DlnaStartPolicy().progressMs,
) {
    private var baselinePositionMs: Long? = null

    fun moved(
        status: CastPlaybackStatus,
        positionMs: Long?,
    ): Boolean {
        if (status != CastPlaybackStatus.Playing || positionMs == null) return false
        val baseline = baselinePositionMs
        if (baseline == null || positionMs < baseline) {
            baselinePositionMs = positionMs
            return false
        }
        return positionMs - baseline >= progressMs
    }
}
