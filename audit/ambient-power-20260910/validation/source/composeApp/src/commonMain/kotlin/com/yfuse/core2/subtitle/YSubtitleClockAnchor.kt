package com.yfuse.core2.subtitle

/** Bounded interpolation between observed player positions; it never becomes a free-running clock. */
data class YSubtitleClockAnchor(
    val positionMs: Long,
    val observedRealtimeNs: Long,
    val advancing: Boolean,
    val speed: Float,
) {
    fun positionAt(realtimeNs: Long): Long {
        if (!advancing || !speed.isFinite() || speed <= 0f) return positionMs
        val elapsedNs = (realtimeNs - observedRealtimeNs).coerceIn(0L, MAX_EXTRAPOLATION_NS)
        return positionMs + (elapsedNs / 1_000_000.0 * speed).toLong()
    }
}

// Native snapshots arrive every 200 ms. Stalled publishers must not keep subtitles moving forever.
private const val MAX_EXTRAPOLATION_NS = 250_000_000L
