package com.yfuse.core2.android

/** Single-use, generation-bound requests; stale demux requests cannot reopen a newer playback. */
internal class YAdaptiveReopenGate(
    private val nowNs: () -> Long = System::nanoTime,
    private val cooldownNs: Long = 10_000_000_000L,
    private val maximumReopensPerGeneration: Int = 8,
    private val reopenWindowNs: Long = 120_000_000_000L,
) {
    init {
        require(cooldownNs >= 0L && maximumReopensPerGeneration > 0 && reopenWindowNs > 0L)
    }

    private var revision = 0L
    private var generation = Long.MIN_VALUE
    private var pendingVariant: String? = null
    private var reopenCount = 0
    private var lastReopenNs: Long? = null
    private val recentReopens = ArrayDeque<Long>()

    @Synchronized
    fun beginTarget(feedbackGeneration: Long): Long {
        updateGeneration(feedbackGeneration)
        pendingVariant = null
        revision += 1L
        return revision
    }

    @Synchronized
    fun propose(
        sourceRevision: Long,
        feedbackGeneration: Long,
        variantId: String,
    ) {
        if (sourceRevision != revision || feedbackGeneration < generation) return
        updateGeneration(feedbackGeneration)
        if (reopenCount >= maximumReopensPerGeneration) return
        val now = nowNs()
        while (recentReopens.firstOrNull()?.let { now - it >= reopenWindowNs } == true) recentReopens.removeFirst()
        // Attaching a rebuilt child advances generation; that must not reset the rolling loop limit.
        if (recentReopens.size >= maximumReopensPerGeneration) return
        if (lastReopenNs?.let { now - it < cooldownNs } == true) return
        if (pendingVariant == null) pendingVariant = variantId
    }

    @Synchronized
    fun discardPending() {
        pendingVariant = null
    }

    @Synchronized
    fun consume(feedbackGeneration: Long): String? {
        if (feedbackGeneration < generation) return null
        updateGeneration(feedbackGeneration)
        val selected = pendingVariant ?: return null
        pendingVariant = null
        reopenCount += 1
        lastReopenNs = nowNs()
        recentReopens.addLast(requireNotNull(lastReopenNs))
        return selected
    }

    private fun updateGeneration(value: Long) {
        if (value != generation) {
            generation = value
            pendingVariant = null
            reopenCount = 0
        }
    }
}
