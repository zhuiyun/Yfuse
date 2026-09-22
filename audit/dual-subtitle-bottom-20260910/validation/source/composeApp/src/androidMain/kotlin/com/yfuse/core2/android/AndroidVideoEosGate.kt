package com.yfuse.core2.android

/** Gives a short Surface tail a render-fence opportunity before the codec enters output EOS. */
internal class AndroidVideoEosGate {
    private var hasVideoInput = false
    private var waitingSinceNs: Long? = null

    fun inputQueued() {
        hasVideoInput = true
    }

    fun mayQueueEndOfStream(
        firstFrameRendered: Boolean,
        nowNs: Long = System.nanoTime(),
    ): Boolean {
        if (!hasVideoInput || firstFrameRendered) return true
        val startedNs = waitingSinceNs ?: nowNs.also { waitingSinceNs = it }
        // Some codecs need EOS to emit even their first frame. Never wait indefinitely for proof.
        return nowNs - startedNs >= 1_000_000_000L
    }

    fun reset() {
        hasVideoInput = false
        waitingSinceNs = null
    }
}
