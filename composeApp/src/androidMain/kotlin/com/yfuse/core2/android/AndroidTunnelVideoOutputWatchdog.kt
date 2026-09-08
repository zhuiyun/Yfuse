package com.yfuse.core2.android

/** Requires real audio progress before judging a tunneled decoder's rendered-frame callback. */
internal class AndroidTunnelVideoOutputWatchdog {
    private var previousAudioUs: Long? = null
    private var lastAudioProgressNs: Long? = null
    private var waitingSinceNs: Long? = null

    fun reset() {
        previousAudioUs = null
        lastAudioProgressNs = null
        waitingSinceNs = null
    }

    fun suspendWaiting() {
        previousAudioUs = null
        waitingSinceNs = null
    }

    fun observe(
        videoQueued: Boolean,
        videoRendered: Boolean,
        outputActive: Boolean,
        audioPositionUs: Long?,
        endOfInput: Boolean,
        nowNs: Long,
    ): Boolean {
        if (!videoQueued || videoRendered) {
            reset()
            return false
        }
        if (!outputActive) {
            suspendWaiting()
            return false
        }
        if (audioPositionUs == null) {
            waitingSinceNs = null
            previousAudioUs = null
            return false
        }
        val previous = previousAudioUs
        previousAudioUs = audioPositionUs
        if (previous != null && audioPositionUs > previous) lastAudioProgressNs = nowNs
        val progressAtNs = lastAudioProgressNs ?: return false
        // A frozen live sink is a source/audio recovery problem. At a confirmed EOF there is no
        // network wait left, and even a short clip must have a bounded final-callback wait.
        if (!endOfInput && nowNs - progressAtNs > 1_000_000_000L) {
            waitingSinceNs = null
            return false
        }
        val startedNs = waitingSinceNs ?: nowNs.also { waitingSinceNs = it }
        return nowNs - startedNs >= 30_000_000_000L
    }
}
