package com.yfuse.feature.player

/** The latest viewer seek wins while a replacement file is opening, including a seek to zero. */
internal class MpvPendingSeek(
    initialPositionMs: Long,
) {
    private var position: Long? = initialPositionMs.coerceAtLeast(0L)

    @Synchronized fun request(positionMs: Long) {
        position = positionMs.coerceAtLeast(0L)
    }

    @Synchronized fun updateIfPending(positionMs: Long) {
        if (position != null) position = positionMs.coerceAtLeast(0L)
    }

    @Synchronized fun take(): Long? = position.also { position = null }

    @Synchronized fun peek(): Long? = position

    @Synchronized fun applyIfPending(apply: (Long) -> Boolean): Boolean {
        val target = position ?: return true
        if (!apply(target)) return false
        position = null
        return true
    }
}
