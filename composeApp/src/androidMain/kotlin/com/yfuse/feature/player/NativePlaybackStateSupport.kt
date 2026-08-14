package com.yfuse.feature.player

import java.util.concurrent.atomic.AtomicInteger

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
 * Poll window used by MDK after changing playback source.
 *
 * Polling runs on a worker dispatcher while encoder cleanup may resume on the main dispatcher, so
 * the counter is atomic rather than tied to either thread.
 */
internal class FallbackSettleWindow(
    private val requiredPolls: Int,
) {
    private val polls = AtomicInteger(Int.MAX_VALUE)

    init {
        require(requiredPolls >= 0) { "Fallback settle poll count must not be negative" }
    }

    val ready: Boolean
        get() = polls.get() >= requiredPolls

    fun tick() {
        polls.getAndUpdate { current ->
            if (current == Int.MAX_VALUE) current else current + 1
        }
    }

    fun restart() {
        polls.set(0)
    }
}
