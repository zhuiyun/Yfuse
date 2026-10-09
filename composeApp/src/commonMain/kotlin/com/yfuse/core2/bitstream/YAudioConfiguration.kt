package com.yfuse.core2.bitstream

/** What Android's audio decoders need beyond the codec private data a container carries. */
object YAudioConfiguration {
    /** Opus's fixed pre-roll after a seek, 80 ms (RFC 7845 section 4.6), as ExoPlayer configures it. */
    const val OPUS_SEEK_PRE_ROLL_NS = 80_000_000L

    /**
     * The pre-skip an `OpusHead` states, in nanoseconds, or null when [header] is not one. Android's
     * Opus decoder takes it as csd-1 and the seek pre-roll as csd-2; given only the header, older
     * decoders read the first two audio packets as those values instead.
     */
    fun opusCodecDelayNs(header: ByteArray): Long? {
        if (header.size < OPUS_HEAD_MIN_BYTES) return null
        if (OPUS_HEAD_MAGIC.indices.any { header[it] != OPUS_HEAD_MAGIC[it] }) return null
        val preSkipSamples = (header[10].toInt() and 0xFF) or ((header[11].toInt() and 0xFF) shl 8)
        return preSkipSamples * NANOS_PER_SECOND / OPUS_SAMPLE_RATE
    }

    /**
     * AAC with no AudioSpecificConfig is ADTS: MPEG-TS and raw `.aac` keep a header on every frame
     * and carry no configuration, while MP4, Matroska and FLV always do. Android's AAC decoder
     * reads such frames only when the format says so (`is-adts`).
     */
    fun aacIsAdts(codecPrivateData: List<ByteArray>): Boolean = codecPrivateData.none { it.isNotEmpty() }
}

private val OPUS_HEAD_MAGIC = "OpusHead".encodeToByteArray()
private const val OPUS_HEAD_MIN_BYTES = 19
private const val OPUS_SAMPLE_RATE = 48_000L
private const val NANOS_PER_SECOND = 1_000_000_000L
