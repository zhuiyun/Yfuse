package com.yfuse.core2.android

/**
 * Diagnostic counters for one NativeDirect session.
 *
 * Pump/backpressure/publication methods stay on the player owner thread. Render callbacks retain
 * their original volatile visibility; this collector does not move callbacks or acquire new locks.
 */
internal class NativeDirectPacingMetrics {
    private var lastQoePublishNs = 0L

    var audioBackpressureCount = 0
        private set

    var slowPumpCount = 0
        private set

    var maximumPumpDurationNs = 0L
        private set

    @Volatile
    var renderedFrameCount = 0L
        private set

    @Volatile
    var longRenderGapCount = 0
        private set

    @Volatile
    var maximumRenderGapNs = 0L
        private set

    @Volatile
    private var lastRenderedRealtimeNs = 0L

    fun recordPump(durationNs: Long) {
        maximumPumpDurationNs = maxOf(maximumPumpDurationNs, durationNs)
        if (durationNs >= SLOW_PUMP_THRESHOLD_NS) slowPumpCount++
    }

    fun recordAudioBackpressure() {
        audioBackpressureCount++
    }

    fun recordRenderedFrame(
        realtimeNs: Long,
        renderTimeTrusted: Boolean,
    ) {
        if (renderTimeTrusted) {
            val previousRealtimeNs = lastRenderedRealtimeNs
            if (previousRealtimeNs > 0L && realtimeNs > previousRealtimeNs) {
                val gapNs = realtimeNs - previousRealtimeNs
                maximumRenderGapNs = maxOf(maximumRenderGapNs, gapNs)
                if (gapNs >= LONG_RENDER_GAP_NS) longRenderGapCount++
            }
        }
        lastRenderedRealtimeNs = if (renderTimeTrusted) realtimeNs else 0L
        renderedFrameCount++
    }

    fun resetRenderAnchor() {
        lastRenderedRealtimeNs = 0L
    }

    fun beginPublication(nowNs: Long): Boolean {
        if (nowNs - lastQoePublishNs < QOE_PUBLISH_INTERVAL_NS) return false
        lastQoePublishNs = nowNs
        return true
    }

    fun finishPublication() {
        maximumPumpDurationNs = 0L
    }

    fun resetMedia() {
        renderedFrameCount = 0L
        longRenderGapCount = 0
        maximumRenderGapNs = 0L
        lastRenderedRealtimeNs = 0L
        audioBackpressureCount = 0
        // Pump stalls explain runtime recovery, so their measurements survive media release.
        lastQoePublishNs = 0L
    }
}

private const val SLOW_PUMP_THRESHOLD_NS = 20_000_000L
private const val LONG_RENDER_GAP_NS = 100_000_000L
private const val QOE_PUBLISH_INTERVAL_NS = 5_000_000_000L
