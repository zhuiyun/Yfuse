package com.yfuse.core2.sync

/** Maps output frame positions to media time, applying a splice only when it reaches the speaker. */
class YPcmTimeline(
    private val sampleRate: Int,
    private val frameBytes: Int,
) {
    private data class Anchor(
        val frame: Long,
        val timeUs: Long,
    )

    private val anchors = ArrayDeque<Anchor>()
    private var submittedBytes = 0L

    init {
        require(sampleRate > 0 && frameBytes > 0)
    }

    /** Called only for bytes the sink accepted, with the timestamp of the first accepted frame. */
    fun record(
        presentationTimeUs: Long,
        bytes: Int,
    ): Long? {
        if (bytes <= 0) return null
        val frame = submittedBytes / frameBytes
        val previous = anchors.lastOrNull()
        val jump = previous?.let { YAvSync.audioTimestampJumpUs(timeAt(it, frame), presentationTimeUs) }
        if (previous == null || jump != null) {
            check(anchors.size < MAX_PENDING_SPLICES) { "Too many unplayed PCM timestamp discontinuities" }
            anchors.addLast(Anchor(frame, presentationTimeUs.coerceAtLeast(0L)))
        }
        submittedBytes += bytes
        return jump
    }

    fun positionUs(playedFrames: Long): Long? {
        while (anchors.size > 1 && anchors[1].frame <= playedFrames) anchors.removeFirst()
        return anchors.firstOrNull()?.let { timeAt(it, playedFrames).coerceAtLeast(0L) }
    }

    fun hasPending(playedFrames: Long): Boolean = submittedBytes / frameBytes - playedFrames > 1L

    private fun timeAt(
        anchor: Anchor,
        frame: Long,
    ): Long = anchor.timeUs + (frame - anchor.frame) * MICROS_PER_SECOND / sampleRate
}

private const val MICROS_PER_SECOND = 1_000_000L
private const val MAX_PENDING_SPLICES = 4_096
