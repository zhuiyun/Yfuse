package com.yfuse.feature.player

/** A read cursor or buffered-duration high-water mark must advance to count as startup progress. */
internal class MpvStartupProgress {
    private var attempt = -1L
    private var cursor = -1L
    private var cacheMs = 0L
    private var progressed = false

    @Synchronized
    fun reset(owner: Long) {
        attempt = owner
        cursor = -1L
        cacheMs = 0L
        progressed = false
    }

    @Synchronized
    fun observe(owner: Long, position: Long? = null, bufferedMs: Long? = null): Boolean {
        if (owner != attempt) return false
        var advanced = false
        if (position != null && position >= 0L) {
            // The first cursor is a baseline: it may be a seek offset rather than received bytes.
            advanced = cursor >= 0L && position > cursor
            cursor = maxOf(cursor, position)
        }
        if (bufferedMs != null && bufferedMs > cacheMs) {
            cacheMs = bufferedMs
            advanced = true
        }
        progressed = progressed || advanced
        return advanced
    }

    @Synchronized
    fun confirmed(owner: Long): Boolean = owner == attempt && progressed
}
