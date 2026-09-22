package com.yfuse.core2.android

import java.util.ArrayDeque

/** A rendered callback proves only frames submitted after the most recent output discontinuity. */
internal class AndroidVideoOutputEpoch {
    private val lock = Any()
    private var generation = 0L
    private var earliestRenderNs = 0L
    private var lastMeasuredRenderNs: Long? = null
    private val submitted = ArrayDeque<Long>()

    @Volatile
    var verified: Boolean = false
        private set

    fun reset(nowNs: Long = System.nanoTime()): Long =
        synchronized(lock) {
            generation++
            earliestRenderNs = nowNs
            lastMeasuredRenderNs = null
            submitted.clear()
            verified = false
            generation
        }

    fun submitted(presentationTimeUs: Long) =
        synchronized(lock) {
            submitted.addLast(presentationTimeUs)
            // MediaCodec owns a bounded number of output buffers. Retain ample room for batched callbacks.
            while (submitted.size > 256) submitted.removeFirst()
        }

    /** [frameIdentityIsolated] requires a codec adapter that rejects old unique codec PTS before this call. */
    fun rendered(
        callbackGeneration: Long,
        presentationTimeUs: Long,
        realtimeNs: Long,
        frameIdentityIsolated: Boolean = false,
        onFirstFrame: () -> Unit,
    ): Boolean =
        synchronized(lock) {
            if (callbackGeneration != generation ||
                (!frameIdentityIsolated && realtimeNs < earliestRenderNs) ||
                !submitted.removeFirstOccurrence(presentationTimeUs)
            ) {
                return@synchronized false
            }
            if (!verified) {
                verified = true
                // Publish within the same discontinuity lock so an old callback cannot race a reset.
                onFirstFrame()
            }
            true
        }

    /** A verified frame may carry an unusable vendor timestamp; never use that timestamp for A/V sync. */
    fun recordRenderTime(
        callbackGeneration: Long,
        realtimeNs: Long,
        nowNs: Long = System.nanoTime(),
    ): Boolean =
        synchronized(lock) {
            if (!verified ||
                callbackGeneration != generation ||
                realtimeNs < earliestRenderNs ||
                realtimeNs > nowNs ||
                lastMeasuredRenderNs?.let { realtimeNs <= it } == true
            ) {
                return@synchronized false
            }
            lastMeasuredRenderNs = realtimeNs
            true
        }
}
