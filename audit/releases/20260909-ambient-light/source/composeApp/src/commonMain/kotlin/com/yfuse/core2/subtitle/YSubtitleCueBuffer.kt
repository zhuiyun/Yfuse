package com.yfuse.core2.subtitle

/** A successful empty display set clears its track; a packet with no output changes nothing. */
sealed interface YSubtitleDecodeResult {
    val cues: List<YSubtitleCue>

    data object NoOutput : YSubtitleDecodeResult {
        override val cues: List<YSubtitleCue> = emptyList()
    }

    /** Timed text/ASS events may overlap intentionally. */
    data class Append(
        override val cues: List<YSubtitleCue>,
    ) : YSubtitleDecodeResult

    /** All rectangles form one display, replaced at [startUs] on this buffer's selected track. */
    data class DisplaySet(
        val startUs: Long,
        override val cues: List<YSubtitleCue>,
    ) : YSubtitleDecodeResult {
        init {
            require(startUs >= 0L)
            require(cues.all { it.startUs == startUs })
        }
    }
}

/**
 * Per-track subtitle history. Read-ahead may deliver future displays: changing their interval ends
 * leaves today's picture visible until the media clock reaches the replacement/clear PTS.
 * Display boundaries, including empty ones, are retained for reordered decode output. Seek and
 * track changes call [clear] after the read-ahead generation has been invalidated.
 */
class YSubtitleCueBuffer(
    private val maximumBitmapBytes: Long = 32L * 1024L * 1024L,
    private val maximumDisplaySets: Int = 512,
) {
    init {
        require(maximumBitmapBytes > 0L && maximumDisplaySets >= 2)
    }

    private val appended = mutableListOf<YSubtitleCue>()
    private val displays = mutableListOf<YSubtitleDecodeResult.DisplaySet>()

    val retainedCueCount: Int get() = appended.size + displays.sumOf { it.cues.size }
    val retainedBitmapBytes: Long get() = displays.sumOf { it.bitmapBytes() }

    var replacementCount: Long = 0L
        private set
    var clearCount: Long = 0L
        private set
    var droppedFutureDisplayCount: Long = 0L
        private set

    fun add(cue: YSubtitleCue) {
        appended.add(cue)
    }

    fun apply(result: YSubtitleDecodeResult) {
        when (result) {
            YSubtitleDecodeResult.NoOutput -> Unit
            is YSubtitleDecodeResult.Append -> appended.addAll(result.cues)
            is YSubtitleDecodeResult.DisplaySet -> {
                if (result.cues.isEmpty()) clearCount++ else replacementCount++
                // A later decode at the same presentation time supersedes the complete display.
                displays.removeAll { it.startUs == result.startUs }
                val index = displays.indexOfFirst { it.startUs > result.startUs }
                displays.add(if (index < 0) displays.size else index, result)
            }
        }
    }

    fun toList(): List<YSubtitleCue> =
        buildList {
            addAll(appended)
            displays.forEachIndexed { index, display ->
                val nextStartUs = displays.getOrNull(index + 1)?.startUs ?: Long.MAX_VALUE
                display.cues.forEach { cue ->
                    add(if (cue.endUs > nextStartUs) cue.copy(endUs = nextStartUs) else cue)
                }
            }
        }

    /** Drop completed pictures and their pixel arrays, retaining one boundary before the window. */
    fun prune(
        oldestRetainedUs: Long,
        positionUs: Long = oldestRetainedUs,
    ) {
        appended.removeAll { it.endUs < oldestRetainedUs }
        while (displays.size > 1 && displays[1].startUs < oldestRetainedUs) {
            displays.removeAt(0)
        }
        // Preserve the boundary even when a finite display expires, without retaining its pixels.
        displays.firstOrNull()?.let { first ->
            if (first.cues.isNotEmpty() && first.cues.all { it.endUs < oldestRetainedUs }) {
                displays[0] = first.copy(cues = emptyList())
            }
        }
        // Time-bounded history can still contain many large images. Evict completed displays first.
        var bytes = retainedBitmapBytes
        while (displays.size > 1 && (bytes >= maximumBitmapBytes || displays.size >= maximumDisplaySets)) {
            if (displays[1].startUs > positionUs) break
            bytes -= displays.removeAt(0).bitmapBytes()
        }
        // Never block the shared demux queue on future subtitles: in a poorly interleaved file
        // that can keep the A/V packets needed to advance the clock behind this same queue.
        // Under exceptional pressure retain the current/nearest pictures and replace distant
        // pictures with empty boundaries, so an omitted picture cannot leave an old one visible.
        for (index in displays.indices.reversed()) {
            if (bytes <= maximumBitmapBytes) break
            val display = displays[index]
            if (display.startUs <= positionUs) break
            val displayBytes = display.bitmapBytes()
            if (displayBytes > 0L) {
                bytes -= displayBytes
                displays[index] = display.copy(cues = emptyList())
                droppedFutureDisplayCount++
            }
        }
        if (displays.size > maximumDisplaySets) {
            val firstOmitted = maximumDisplaySets - 1
            val boundary = displays[firstOmitted].copy(cues = emptyList())
            droppedFutureDisplayCount += displays.subList(firstOmitted, displays.size).count { it.cues.isNotEmpty() }
            displays.subList(firstOmitted, displays.size).clear()
            displays.add(boundary)
        }
    }

    fun activeDisplayCount(positionUs: Long): Int =
        if (displays.lastOrNull { it.startUs <= positionUs }?.cues?.any { positionUs < it.endUs } == true) 1 else 0

    fun clear() {
        appended.clear()
        displays.clear()
        replacementCount = 0L
        clearCount = 0L
        droppedFutureDisplayCount = 0L
    }
}

private fun YSubtitleDecodeResult.DisplaySet.bitmapBytes(): Long =
    cues.sumOf {
        (it.payload as? YSubtitlePayload.BitmapArgb)
            ?.pixels
            ?.size
            ?.toLong()
            ?.times(4L) ?: 0L
    }
