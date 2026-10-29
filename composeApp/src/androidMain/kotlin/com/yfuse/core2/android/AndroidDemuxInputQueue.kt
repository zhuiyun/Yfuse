package com.yfuse.core2.android

import com.yfuse.core2.demux.YTrackId

internal data class DemuxInputPumpResult(
    val didWork: Boolean,
    val starved: Boolean = false,
    val ended: Boolean = false,
)

/** Owns per-track backpressure and invalidates a pump batch when seek/fallback changes input. */
internal class AndroidDemuxInputQueue {
    private val pending = linkedMapOf<YTrackId, YQueuedDemuxResult.Sample>()
    private val blocked = mutableSetOf<YTrackId>()
    var generation: Long = 0
        private set
    val pendingCount: Int get() = pending.size

    fun retain(sample: YQueuedDemuxResult.Sample) {
        pending[sample.value.trackId] = sample
    }

    fun clear() {
        pending.clear()
        blocked.clear()
    }

    fun advanceGeneration() {
        generation++
    }

    fun pump(
        poll: (Set<YTrackId>) -> YQueuedDemuxResult,
        queue: (YQueuedDemuxResult.Sample) -> Boolean,
    ): DemuxInputPumpResult = pump(poll, queue, null)

    fun pump(
        poll: (Set<YTrackId>) -> YQueuedDemuxResult,
        queue: (YQueuedDemuxResult.Sample) -> Boolean,
        onSourceFailure: ((Throwable) -> Unit)?,
    ): DemuxInputPumpResult {
        val startedGeneration = generation
        var didWork = false
        blocked.clear()
        for ((track, sample) in pending.toMap()) {
            val accepted = queue(sample)
            if (startedGeneration != generation) return DemuxInputPumpResult(true)
            if (accepted) {
                pending.remove(track)
                didWork = true
            } else {
                blocked += track
            }
        }
        repeat(4) {
            val sample =
                when (val next = poll(blocked)) {
                    is YQueuedDemuxResult.Sample -> next
                    is YQueuedDemuxResult.Failed -> {
                        if (pending.isNotEmpty()) return DemuxInputPumpResult(didWork)
                        val defer = onSourceFailure ?: throw next.cause
                        defer(next.cause)
                        return DemuxInputPumpResult(didWork = true, ended = true)
                    }
                    YQueuedDemuxResult.Empty -> return DemuxInputPumpResult(didWork, starved = pending.isEmpty())
                    YQueuedDemuxResult.EndOfInput ->
                        return DemuxInputPumpResult(didWork || pending.isEmpty(), ended = pending.isEmpty())
                }
            val accepted = queue(sample)
            if (startedGeneration != generation) return DemuxInputPumpResult(true)
            if (accepted) {
                didWork = true
            } else {
                retain(sample)
                blocked += sample.value.trackId
            }
        }
        return DemuxInputPumpResult(didWork)
    }
}
