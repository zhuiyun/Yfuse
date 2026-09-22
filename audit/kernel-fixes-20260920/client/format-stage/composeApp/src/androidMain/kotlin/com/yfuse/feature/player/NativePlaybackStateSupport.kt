package com.yfuse.feature.player

internal fun mpvDynamicRange(gamma: String): String =
    when (gamma.trim().lowercase()) {
        "pq" -> "HDR10 / PQ"
        "hlg" -> "HLG"
        "linear", "gamma1.8", "gamma2.0", "gamma2.2", "gamma2.4", "bt.1886", "srgb" -> "SDR"
        else -> gamma.uppercase()
    }

/**
 * Distinguishes mpv's expected END_FILE for `loadfile replace`/`stop` from a failed stream.
 *
 * Commands and events arrive on different threads. One expected end is reserved for each
 * intentional replacement/stop so the engine never mistakes its own lifecycle transition for a
 * decoder failure.
 */
internal class MpvEndFileTracker {
    private var hasFileOrPending = false
    private var expectedEnds = 0

    @Synchronized
    fun beforeLoad(): Boolean {
        val replacing = hasFileOrPending
        hasFileOrPending = true
        if (replacing) expectedEnds++
        return replacing
    }

    @Synchronized
    fun rollbackLoad(replacing: Boolean) {
        if (replacing) {
            if (expectedEnds > 0) expectedEnds--
            hasFileOrPending = true
        } else {
            hasFileOrPending = false
        }
    }

    @Synchronized
    fun beforeStop(): Boolean {
        val stopping = hasFileOrPending
        hasFileOrPending = false
        if (stopping) expectedEnds++
        return stopping
    }

    @Synchronized
    fun rollbackStop(stopping: Boolean) {
        if (!stopping) return
        if (expectedEnds > 0) expectedEnds--
        hasFileOrPending = true
    }

    @Synchronized
    fun consumeExpectedEnd(): Boolean {
        if (expectedEnds <= 0) {
            hasFileOrPending = false
            return false
        }
        expectedEnds--
        return true
    }

    @get:Synchronized
    internal val pendingExpectedEnds: Int
        get() = expectedEnds
}

/**
 * Keeps the previous MDK status out of a replacement load. The window starts when setMedia is
 * submitted, so neither encoder cleanup nor a burst of native events consumes its duration.
 */
internal class MdkLoadStateGate(
    private val settleDurationMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var epoch = 0L
    private var submittedAtMs: Long? = null

    init {
        require(settleDurationMs >= 0L)
    }

    @get:Synchronized
    val currentEpoch: Long get() = epoch

    @Synchronized
    fun beginLoad(): Long {
        submittedAtMs = null
        return ++epoch
    }

    @Synchronized
    fun isCurrent(expectedEpoch: Long): Boolean = expectedEpoch == epoch

    @Synchronized
    fun didSubmit(expectedEpoch: Long): Boolean {
        if (!isCurrent(expectedEpoch)) return false
        submittedAtMs = nowMs()
        return true
    }

    @Synchronized
    fun observe(
        expectedEpoch: Long,
        rawInvalid: Boolean,
    ): MdkLoadStatus {
        if (!isCurrent(expectedEpoch)) return MdkLoadStatus.Stale
        val submitted = submittedAtMs ?: return MdkLoadStatus.Waiting
        if (!rawInvalid) return MdkLoadStatus.Active
        // The first source has no previous INVALID state to outlive.
        return if (epoch > 1L && nowMs() - submitted < settleDurationMs) {
            MdkLoadStatus.Waiting
        } else {
            MdkLoadStatus.Invalid
        }
    }
}

internal enum class MdkLoadStatus { Stale, Waiting, Active, Invalid }

/**
 * Requires the replacement media to leave MDK's terminal state before accepting a new end.
 * MDK can keep STATUS_END set briefly after setMedia(), which must not skip the next queue item.
 */
internal class MdkEndStateGate {
    private var observedActiveState = false

    @Synchronized
    fun restart() {
        observedActiveState = false
    }

    @Synchronized
    fun observe(rawEnded: Boolean): Boolean {
        if (!rawEnded) {
            observedActiveState = true
            return false
        }
        return observedActiveState
    }
}
