package com.yfuse.core2.subtitle

/**
 * MediaExtractor does not expose a duration for timed-text samples. A later plain-text sample
 * bounds the previous display; an empty timed-text sample clears it. Packets at the same PTS
 * remain one display group. Authored ASS layers keep their independent event lifetimes.
 */
fun appendUntimedTextSubtitlePacket(
    cues: MutableList<YSubtitleCue>,
    data: ByteArray,
    format: YSubtitleFormat,
    presentationTimeUs: Long,
    id: String,
) {
    val startUs = presentationTimeUs.coerceAtLeast(0L)
    val next = YEmbeddedSubtitleDecoder.decode(data, format, startUs, durationUs = null, id = id)
    if (format in REPLACING_TEXT_FORMATS) {
        // An empty display also clears text emitted earlier at the exact same timestamp.
        if (next == null) cues.removeAll { it.startUs == startUs }
        for (index in cues.indices) {
            val previous = cues[index]
            if (previous.startUs < startUs && previous.endUs > startUs) {
                cues[index] = previous.copy(endUs = startUs)
            }
        }
    }
    next?.let {
        // A repeated restore/sample delivery must not create a duplicate overlay.
        if (cues.none { it.id == next.id && it.payload == next.payload }) cues.add(next)
    }
}

private val REPLACING_TEXT_FORMATS = setOf(YSubtitleFormat.Tx3g, YSubtitleFormat.Srt, YSubtitleFormat.WebVtt)
