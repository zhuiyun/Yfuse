package com.yfuse.feature.player

/** Attempt every owned resource even when an earlier cleanup fails; never hide incomplete cleanup. */
internal class PlaybackResourceCleanup {
    private var failure: Throwable? = null

    fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            val previous = failure
            if (previous == null) {
                failure = error
            } else if (previous !== error) {
                previous.addSuppressed(error)
            }
        }
    }

    fun throwIfFailed() {
        failure?.let { throw it }
    }
}
