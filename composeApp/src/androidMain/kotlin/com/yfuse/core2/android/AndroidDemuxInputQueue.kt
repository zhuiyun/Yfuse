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
                    is YQueuedDemuxResult.Failed -> throw next.cause
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
