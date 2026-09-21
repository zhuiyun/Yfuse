package com.yfuse.core2.android

/** Monotonic progress monitor scoped to one uninterrupted output wait, never user pauses. */
internal class AndroidBufferWaitMonitor {
    private var startedNs: Long? = null
    private var progressedNs = 0L
    private var lastPackets = -1L
    private var generation = -1L

    fun reset() {
        startedNs = null
        lastPackets = -1L
        generation = -1L
    }

    fun observe(
        nowNs: Long,
        packets: Long,
        sourceGeneration: Long,
    ): Long {
        if (startedNs == null || generation != sourceGeneration) {
            startedNs = nowNs
            progressedNs = nowNs
            generation = sourceGeneration
        }
        if (packets != lastPackets) {
            lastPackets = packets
            progressedNs = nowNs
        }
        return (nowNs - requireNotNull(startedNs)).coerceAtLeast(0L) / 1_000L
    }

    // Longer than the transport's 30-second total range budget, including internal retries.
    fun stalled(nowNs: Long): Boolean = startedNs != null && nowNs - progressedNs >= 35_000_000_000L
}
