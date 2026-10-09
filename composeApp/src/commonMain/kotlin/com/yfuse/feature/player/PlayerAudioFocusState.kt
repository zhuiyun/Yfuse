package com.yfuse.feature.player

/** Tracks focus ownership and one pending resume without depending on Android callbacks. */
internal class PlayerAudioFocusState {
    private var generation = 0L
    private var activeRequest: Long? = null
    private var resumeAfterTransientLoss = false

    var hasFocus = false
        private set

    fun beginRequest(): Long {
        abandon()
        return (++generation).also { activeRequest = it }
    }

    fun isActive(request: Long): Boolean = activeRequest == request

    fun requested(granted: Boolean) {
        hasFocus = activeRequest != null && granted
        if (hasFocus) resumeAfterTransientLoss = false
    }

    fun lost(
        request: Long,
        transient: Boolean,
        playbackRequested: Boolean,
    ): Boolean {
        if (!isActive(request)) return false
        hasFocus = false
        // Repeated transient losses see the pause we requested, not the viewer's original intent.
        resumeAfterTransientLoss = transient && (resumeAfterTransientLoss || playbackRequested)
        return true
    }

    fun gained(
        request: Long,
        canResume: Boolean,
    ): Boolean {
        if (!isActive(request)) return false
        hasFocus = true
        val resume = resumeAfterTransientLoss && canResume
        resumeAfterTransientLoss = false
        return resume
    }

    fun cancelResume() {
        resumeAfterTransientLoss = false
    }

    fun abandon() {
        activeRequest = null
        hasFocus = false
        cancelResume()
    }
}
