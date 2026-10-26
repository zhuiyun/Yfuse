package com.yfuse.core2.android

/** Detects an audio decoder that accepts packets but never reports its PCM output format. */
internal class AudioDecoderStartupWatchdog {
    private var inputCount = 0
    private var waitingSinceNs: Long? = null
    private var attempted = false

    fun inputQueued() {
        if (inputCount < MIN_INPUT_PACKETS) inputCount++
    }

    fun pause() {
        waitingSinceNs = null
    }

    fun reset() {
        inputCount = 0
        waitingSinceNs = null
        attempted = false
    }

    fun shouldFallback(
        nowNs: Long,
        eligible: Boolean,
    ): Boolean {
        if (!eligible || inputCount < MIN_INPUT_PACKETS) {
            waitingSinceNs = null
            return false
        }
        if (attempted) return false
        val started = waitingSinceNs ?: nowNs.also { waitingSinceNs = it }
        if (nowNs - started < STARTUP_TIMEOUT_NS) return false
        attempted = true
        return true
    }
}

private const val MIN_INPUT_PACKETS = 4
private const val STARTUP_TIMEOUT_NS = 3_000_000_000L
