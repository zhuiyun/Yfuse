package com.yfuse.feature.player

/** Background audio owns a video-suspension request, not permission to resume paused playback. */
internal class BackgroundAudioLifecycle {
    var active: Boolean = false
        private set
    private var unsupported = false

    fun activate(suspendVideo: () -> Unit) {
        if (active) return
        active = true
        suspendVideo()
    }

    /** A route can lose support after background audio began; retire its pending video request too. */
    fun pauseUnsupported(
        pausePlayback: () -> Unit,
        restoreVideo: () -> Unit,
    ) {
        active = false
        unsupported = true
        try {
            pausePlayback()
        } finally {
            restoreVideo()
        }
    }

    /** Returns a one-time explanation for a capability-triggered pause, without requesting play. */
    fun foreground(restoreVideo: () -> Unit): Boolean {
        val explainPause = unsupported
        val restore = active || unsupported
        active = false
        unsupported = false
        if (restore) restoreVideo()
        return explainPause
    }
}
