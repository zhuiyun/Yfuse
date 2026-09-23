package com.yfuse.core2.android

/**
 * The runs of the file MediaExtractor is reading.
 *
 * An interleaved file is read as one forward run. A non-interleaved MP4 keeps each track in a
 * run of its own and the extractor alternates between them - one diagnostic had video about
 * 570 MB into the file and audio 6-14 MB in. With a single read position every alternation
 * looked like a seek: the other run's read-ahead was cancelled (1,820 ranges and 119 MB in
 * eleven minutes) and its next read waited on the network, so a 6 Mbps title on an 11 Mbps link
 * still rebuffered for 23 s. Tracking two heads lets each run keep a window of its own.
 *
 * Not thread-safe: the owner calls it under its own monitor.
 */
internal class YTransportReadHeads(
    private val nearBlocks: Long = TRANSPORT_READ_HEAD_NEAR_BLOCKS,
    private val liveNs: Long = TRANSPORT_READ_HEAD_LIVE_NS,
) {
    private class Head(
        var block: Long,
        var touchedNs: Long,
    ) {
        /** Forward blocks moved since the head appeared: the busier run is the heavier track. */
        var advancedBlocks = 0L

        /**
         * Times the extractor came back to this run from the other one. A position left behind
         * by a seek is never returned to, so it never earns a read-ahead window of its own.
         */
        var returns = 0
    }

    private val heads = ArrayList<Head>(MAX_READ_HEADS)
    private var current: Head? = null

    /**
     * Records a read of [blockIndex]. False when it continues no live head: a seek, or the first
     * read of a second run. Every extractor read counts, served from memory or not, so a
     * low-bitrate audio run whose next block is minutes away still stays live.
     */
    fun record(
        blockIndex: Long,
        nowNs: Long,
    ): Boolean {
        heads.removeAll { nowNs - it.touchedNs > liveNs }
        heads.firstOrNull { blockIndex.isNear(it.block) }?.let { head ->
            if (head !== current) head.returns++
            if (blockIndex > head.block) head.advancedBlocks += blockIndex - head.block
            head.block = blockIndex
            head.touchedNs = nowNs
            current = head
            return true
        }
        if (heads.size == MAX_READ_HEADS) heads.remove(heads.minBy(Head::touchedNs))
        current = Head(blockIndex, nowNs).also(heads::add)
        return false
    }

    /**
     * Blocks the other live runs are reading, excluding the run around [blockIndex]. Only runs
     * the extractor has already come back to count: that is what tells a second track apart from
     * the place a seek left.
     */
    fun otherHeads(
        blockIndex: Long,
        nowNs: Long,
    ): List<Long> =
        heads
            .filter { nowNs - it.touchedNs <= liveNs && it.returns > 0 && !blockIndex.isNear(it.block) }
            .map(Head::block)

    /**
     * While two runs alternate, the one that moves the most bytes - the video, in a
     * non-interleaved file. Null for an ordinary single-run read, whose owner keeps using its
     * latest read position.
     */
    fun primaryBlock(nowNs: Long): Long? {
        val live = heads.filter { nowNs - it.touchedNs <= liveNs }
        if (live.size < MAX_READ_HEADS || live.any { it.returns == 0 }) return null
        return live.maxWith(compareBy<Head>({ it.advancedBlocks }, { it.touchedNs })).block
    }

    fun clear() {
        heads.clear()
        current = null
    }

    private fun Long.isNear(head: Long): Boolean = this in (head - nearBlocks)..(head + nearBlocks)
}

/** A read this close to a head continues its run; one farther away starts another. */
internal const val TRANSPORT_READ_HEAD_NEAR_BLOCKS = 4L

/** A run not read for this long has been left behind by a seek. */
internal const val TRANSPORT_READ_HEAD_LIVE_NS = 5_000_000_000L

/** Video and one audio track. More runs are not worth splitting the prefetch budget for. */
private const val MAX_READ_HEADS = 2
