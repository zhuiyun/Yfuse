package com.yfuse.core.personal

/** Monotonic time, verified against advancing media time: seeks and frozen playback add no time. */
class ViewingTimeCounter {
    private data class Sample(
        val monotonicMs: Long,
        val positionMs: Long,
        val active: Boolean,
        val speed: Float,
    )

    private var previous: Sample? = null
    private var elapsedTotalMs = 0L
    private var mediaTotalMs = 0.0
    private var reportedMs = 0L

    private fun resetInterval(): Long {
        elapsedTotalMs = 0L
        mediaTotalMs = 0.0
        reportedMs = 0L
        return 0L
    }

    fun sample(
        monotonicMs: Long,
        positionMs: Long,
        active: Boolean,
        speed: Float = 1f,
    ): Long {
        val next = Sample(monotonicMs, positionMs, active, speed.takeIf { it.isFinite() && it > 0f } ?: 1f)
        val old = previous
        previous = next
        if (old == null || !old.active || !next.active || old.speed != next.speed) return resetInterval()
        val elapsed = next.monotonicMs - old.monotonicMs
        val advance = next.positionMs - old.positionMs
        // A suspended process and a forward/backward seek cannot masquerade as continuous watching.
        if (elapsed !in 1L..10_000L || advance <= 0L || advance > elapsed * next.speed + 1_500L) return resetInterval()
        // Engine positions arrive at frame boundaries. Carry their rounding across consecutive
        // samples so 967ms followed by 1033ms counts the full two seconds, without counting a stall.
        elapsedTotalMs += elapsed
        mediaTotalMs += advance / next.speed.toDouble()
        val verified = minOf(elapsedTotalMs, mediaTotalMs.toLong())
        return (verified - reportedMs).also { reportedMs = verified }
    }
}
