package com.yfuse.core2.android

/** Owned by the GL thread. A texture can skip frames, but must never confirm a different epoch. */
internal class Anime4KFrameLedger(
    private val capacity: Int = 64,
) {
    private val pending = ArrayDeque<Pair<Long, Long>>()

    init {
        require(capacity > 0)
    }

    fun scheduled(
        timestampNs: Long,
        codecTimeUs: Long,
    ) {
        if (pending.size >= capacity) pending.removeFirst()
        pending.addLast(timestampNs to codecTimeUs)
    }

    fun presented(timestampNs: Long): Long? {
        // Timed release carries wall time; immediate release carries the codec presentation time.
        val match = pending.lastOrNull { it.first == timestampNs || it.second * 1_000L == timestampNs } ?: return null
        while (pending.isNotEmpty()) {
            if (pending.removeFirst() === match) break
        }
        return match.second
    }

    fun clear() = pending.clear()
}
