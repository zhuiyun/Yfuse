package com.yfuse.core2.android

/**
 * Codec timestamps are opaque identities on an app-paced direct Surface. Original media timestamps
 * remain exact, including duplicate PTS and decode-order reordering. Tokens never repeat after flush.
 * All methods also serve the codec callback thread, independently of the video owner's command loop.
 */
internal class CodecFrameTimestampMapper(
    private val maximumTrackedFrames: Int = 1_024,
    private val maximumReleasedFrames: Int = 256,
    firstToken: Long = 1L,
) {
    private var nextToken = firstToken
    private val queued = LinkedHashMap<Long, Long>()
    private val owned = LinkedHashMap<Long, Long>()
    private val released = LinkedHashMap<Long, Long>()

    init {
        require(maximumTrackedFrames > 0 && maximumReleasedFrames > 0)
        require(firstToken in 1L..MAX_CODEC_TIMESTAMP_US)
    }

    @Synchronized
    fun canQueue(): Boolean = queued.size + owned.size < maximumTrackedFrames

    /** Called only after an input buffer is acquired; TryAgain must not allocate an identity. */
    @Synchronized
    fun queue(mediaTimeUs: Long): Long {
        check(canQueue()) { "Too many outstanding codec frame timestamps" }
        check(nextToken in 1L..MAX_CODEC_TIMESTAMP_US) { "Codec frame timestamp identities exhausted" }
        val token = nextToken++
        queued[token] = mediaTimeUs
        return token
    }

    /** An unsuccessful platform enqueue consumes its identity but not a tracking slot. */
    @Synchronized
    fun cancelQueue(token: Long) {
        queued.remove(token)
    }

    @Synchronized
    fun dequeue(token: Long): Long? {
        val mediaTimeUs = queued.remove(token) ?: return null
        owned[token] = mediaTimeUs
        return mediaTimeUs
    }

    /** Register before releaseOutputBuffer, which may cause an immediate callback on another thread. */
    @Synchronized
    fun release(
        token: Long,
        render: Boolean,
    ): Boolean {
        val mediaTimeUs = owned.remove(token) ?: return false
        if (render) {
            released[token] = mediaTimeUs
            // Missing OEM callbacks cannot retain the whole item. Very late evidence is discarded.
            while (released.size > maximumReleasedFrames) released.remove(released.keys.first())
        }
        return true
    }

    @Synchronized
    fun cancelRelease(token: Long) {
        released.remove(token)
    }

    @Synchronized
    fun rendered(token: Long): Long? = released.remove(token)

    /** Preserve input and app-owned output mappings when a caller replaces only its observer. */
    @Synchronized
    fun listenerChanged() {
        released.clear()
    }

    @Synchronized
    fun flush() {
        queued.clear()
        owned.clear()
        released.clear()
    }

    internal companion object {
        // Remain representable if an OEM converts the opaque microsecond timestamp to nanoseconds.
        const val MAX_CODEC_TIMESTAMP_US = Long.MAX_VALUE / 1_000L
    }
}
