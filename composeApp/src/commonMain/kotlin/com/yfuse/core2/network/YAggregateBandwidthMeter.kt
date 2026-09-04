package com.yfuse.core2.network

import kotlin.math.sqrt

/**
 * Link-bandwidth estimate for transports that fetch several byte ranges at once.
 *
 * Two things make a naive per-request estimate wrong here:
 *
 * 1. **Concurrency.** Timing each range on its own wall clock while up to
 *    `MAX_TRANSPORT_PREFETCH_CONCURRENCY` of them overlap measures one connection's share of the
 *    link, not the link. The estimate is systematically low, which then feeds ABR and the buffer
 *    planner. This meter instead accumulates every transferred byte over a *busy period* - the
 *    maximal interval during which at least one transfer was in flight - so overlapping ranges add
 *    up instead of dividing the result. A busy period contains no idle time by construction, so
 *    dividing its bytes by its duration is the aggregate throughput.
 * 2. **A single EWMA.** One exponential average tracks the last burst rather than the sustained
 *    rate, so a CDN burst or one slow origin moves it much further than it should. Samples are
 *    kept in a bounded sliding window weighted by `sqrt(bytes)` and queried as a weighted median,
 *    which is what makes a single outlier unable to move the estimate.
 *
 * Instances are safe to call from the transfer threads.
 */
class YAggregateBandwidthMeter(
    private val minimumSampleBytes: Long = DEFAULT_MINIMUM_SAMPLE_BYTES,
    private val minimumSampleNanos: Long = DEFAULT_MINIMUM_SAMPLE_NANOS,
    private val maximumWeight: Double = DEFAULT_MAXIMUM_WEIGHT,
) {
    init {
        require(minimumSampleBytes > 0L)
        require(minimumSampleNanos > 0L)
        require(maximumWeight > 0.0)
    }

    private val lock = Any()
    private val samples = ArrayDeque<WeightedSample>()
    private var totalWeight = 0.0
    private var activeTransfers = 0
    private var busyPeriodStartedAtNs = 0L
    private var busyPeriodBytes = 0L

    /** Call when a range transfer begins, before any byte of it is read. */
    fun onTransferStarted(nowNs: Long) {
        synchronized(lock) {
            if (activeTransfers == 0) {
                busyPeriodStartedAtNs = nowNs
                busyPeriodBytes = 0L
            }
            activeTransfers++
        }
    }

    /**
     * Call exactly once per [onTransferStarted], with the bytes that transfer actually delivered.
     *
     * Returns the completed busy-period sample when this call ended the busy period, so callers
     * that want to forward one aggregate measurement per period can do so without polling.
     */
    fun onTransferFinished(
        bytes: Long,
        nowNs: Long,
    ): YBandwidthSample? =
        synchronized(lock) {
            busyPeriodBytes += bytes.coerceAtLeast(0L)
            activeTransfers = (activeTransfers - 1).coerceAtLeast(0)
            if (activeTransfers > 0) return@synchronized null
            val elapsedNs = (nowNs - busyPeriodStartedAtNs).coerceAtLeast(0L)
            val periodBytes = busyPeriodBytes
            busyPeriodBytes = 0L
            if (periodBytes < minimumSampleBytes || elapsedNs < minimumSampleNanos) {
                return@synchronized null
            }
            val bitsPerSecond =
                periodBytes
                    .saturatedMultiply(BITS_PER_BYTE * NANOS_PER_SECOND)
                    .div(elapsedNs)
            record(bitsPerSecond, periodBytes)
            YBandwidthSample(
                bytes = periodBytes,
                durationMs = (elapsedNs / NANOS_PER_MILLISECOND).coerceAtLeast(1L),
                bitsPerSecond = bitsPerSecond,
            )
        }

    /** Weighted median of the retained window, or 0 when nothing has been measured yet. */
    fun bitsPerSecond(): Long =
        synchronized(lock) {
            if (samples.isEmpty()) return@synchronized 0L
            val target = totalWeight / 2.0
            var accumulated = 0.0
            for (sample in samples.sortedBy(WeightedSample::bitsPerSecond)) {
                accumulated += sample.weight
                if (accumulated >= target) return@synchronized sample.bitsPerSecond
            }
            samples.last().bitsPerSecond
        }

    fun reset() {
        synchronized(lock) {
            samples.clear()
            totalWeight = 0.0
            activeTransfers = 0
            busyPeriodBytes = 0L
        }
    }

    private fun record(
        bitsPerSecond: Long,
        bytes: Long,
    ) {
        val weight = sqrt(bytes.toDouble())
        samples.addLast(WeightedSample(bitsPerSecond = bitsPerSecond, weight = weight))
        totalWeight += weight
        while (totalWeight > maximumWeight && samples.size > 1) {
            totalWeight -= samples.removeFirst().weight
        }
    }

    private data class WeightedSample(
        val bitsPerSecond: Long,
        val weight: Double,
    )
}

data class YBandwidthSample(
    val bytes: Long,
    val durationMs: Long,
    val bitsPerSecond: Long,
)

private fun Long.saturatedMultiply(other: Long): Long =
    if (other != 0L && this > Long.MAX_VALUE / other) Long.MAX_VALUE else this * other

private const val BITS_PER_BYTE = 8L
private const val NANOS_PER_SECOND = 1_000_000_000L
private const val NANOS_PER_MILLISECOND = 1_000_000L

/** Below this a busy period is startup noise rather than a throughput measurement. */
private const val DEFAULT_MINIMUM_SAMPLE_BYTES = 128L * 1024L
private const val DEFAULT_MINIMUM_SAMPLE_NANOS = 20L * 1_000_000L

/** With `sqrt(bytes)` weights this keeps roughly the last twenty busy periods of a few MiB each. */
private const val DEFAULT_MAXIMUM_WEIGHT = 32.0 * 1024.0
