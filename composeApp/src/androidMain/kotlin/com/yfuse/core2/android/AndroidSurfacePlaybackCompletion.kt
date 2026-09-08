package com.yfuse.core2.android

/** Surface release completion is independent of the optional frame-rendered notification. */
internal class AndroidSurfacePlaybackCompletion {
    private var lastReleaseDeadlineNs: Long? = null

    fun frameReleased(releaseTimeNs: Long) {
        lastReleaseDeadlineNs = lastReleaseDeadlineNs?.let { maxOf(it, releaseTimeNs) } ?: releaseTimeNs
    }

    fun ended(
        decoderOutputEnded: Boolean,
        audioOutputEnded: Boolean,
        pausedPreviewPending: Boolean,
        nowNs: Long = System.nanoTime(),
    ): Boolean =
        decoderOutputEnded &&
            audioOutputEnded &&
            !pausedPreviewPending &&
            (lastReleaseDeadlineNs?.let { nowNs >= it } ?: true)

    fun reset() {
        lastReleaseDeadlineNs = null
    }
}
