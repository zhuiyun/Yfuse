package com.yfuse.core2.sync

/**
 * Converts container timestamps into the product timeline used by YPlayer.
 *
 * MediaExtractor/FFmpeg may expose the first compressed sample at an arbitrary PTS (for example
 * 1.8 s). Legacy players rebase that media start to 0, so Core2 must do the same before PTS enters
 * MediaCodec, AudioTrack or YMediaClock. Seeks perform the inverse conversion back to source time.
 *
 * One instance belongs to one demux session and is single-owner with that demuxer.
 */
class YMediaTimestampTimeline {
    private var sourceOriginUs: Long? = null

    val established: Boolean get() = sourceOriginUs != null

    val originUs: Long get() = sourceOriginUs ?: 0L

    fun reset() {
        sourceOriginUs = null
    }

    fun establish(rawPresentationTimeUs: Long): Long {
        sourceOriginUs?.let { return it }
        sourceOriginUs = rawPresentationTimeUs
        return rawPresentationTimeUs
    }

    /** Presentation time exposed to the player/UI, always relative to the first source PTS. */
    fun presentationTimeUs(rawPresentationTimeUs: Long): Long {
        val origin = sourceOriginUs ?: establish(rawPresentationTimeUs)
        return subtractSaturated(rawPresentationTimeUs, origin).coerceAtLeast(0L)
    }

    /** Decode time keeps negative preroll when DTS precedes the first presentation timestamp. */
    fun decodeTimeUs(rawDecodeTimeUs: Long): Long {
        val origin = sourceOriginUs ?: establish(rawDecodeTimeUs)
        return subtractSaturated(rawDecodeTimeUs, origin)
    }

    /** Converts a YPlayer-relative seek target back into the container's timestamp domain. */
    fun sourceTimeUs(relativePositionUs: Long): Long {
        val relative = relativePositionUs.coerceAtLeast(0L)
        val origin = sourceOriginUs ?: 0L
        return addSaturated(relative, origin).coerceAtLeast(0L)
    }
}

private fun addSaturated(
    left: Long,
    right: Long,
): Long =
    when {
        right > 0L && left > Long.MAX_VALUE - right -> Long.MAX_VALUE
        right < 0L && left < Long.MIN_VALUE - right -> Long.MIN_VALUE
        else -> left + right
    }

private fun subtractSaturated(
    left: Long,
    right: Long,
): Long =
    when {
        right > 0L && left < Long.MIN_VALUE + right -> Long.MIN_VALUE
        right < 0L && left > Long.MAX_VALUE + right -> Long.MAX_VALUE
        else -> left - right
    }
