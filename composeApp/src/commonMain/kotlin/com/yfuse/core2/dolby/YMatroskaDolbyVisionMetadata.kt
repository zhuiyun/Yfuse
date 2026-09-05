package com.yfuse.core2.dolby

/** Dolby Vision configuration recovered from a Matroska TrackEntry. */
internal data class YMatroskaDolbyVisionMetadata(
    val codecId: String,
    val blockAddIdType: Long,
    val config: YDolbyVisionConfig,
)

/** Result of a bounded Matroska metadata read. */
internal sealed interface YMatroskaDolbyVisionMetadataResult {
    data class Found(
        val metadata: YMatroskaDolbyVisionMetadata,
    ) : YMatroskaDolbyVisionMetadataResult

    /** A complete Tracks element was parsed without a valid Dolby Vision mapping. */
    data object Absent : YMatroskaDolbyVisionMetadataResult

    /** More source bytes are required before the Tracks element can be decided safely. */
    data object Truncated : YMatroskaDolbyVisionMetadataResult

    /** The supplied bytes do not begin with a valid Matroska/EBML document. */
    data object Invalid : YMatroskaDolbyVisionMetadataResult
}

/**
 * Small, bounded EBML reader for the Matroska metadata that Android MediaExtractor omits on some
 * vendor builds. It does not demux packets or decode media; playback remains
 * MediaExtractor -> MediaCodec -> Surface/AudioTrack.
 */
internal object YMatroskaDolbyVisionMetadataParser {
    fun parse(bytes: ByteArray): YMatroskaDolbyVisionMetadataResult {
        if (bytes.isEmpty()) return YMatroskaDolbyVisionMetadataResult.Truncated
        val ebml = readElement(bytes, 0) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
        if (ebml.id != ID_EBML) return YMatroskaDolbyVisionMetadataResult.Invalid
        var position = ebml.completeEnd(bytes.size) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
        while (position < bytes.size) {
            val element = readElement(bytes, position) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
            if (element.id == ID_SEGMENT) {
                return parseSegment(bytes, element.dataStart, element.availableEnd(bytes.size))
            }
            position = element.completeEnd(bytes.size) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
        }
        return YMatroskaDolbyVisionMetadataResult.Truncated
    }

    private fun parseSegment(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaDolbyVisionMetadataResult {
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
            when (element.id) {
                ID_TRACKS -> {
                    val tracksEnd = element.completeEnd(end) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
                    return parseTracks(bytes, element.dataStart, tracksEnd)
                }
                ID_CLUSTER -> return YMatroskaDolbyVisionMetadataResult.Truncated
            }
            position = element.completeEnd(end) ?: return YMatroskaDolbyVisionMetadataResult.Truncated
        }
        return YMatroskaDolbyVisionMetadataResult.Truncated
    }

    private fun parseTracks(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaDolbyVisionMetadataResult {
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return YMatroskaDolbyVisionMetadataResult.Invalid
            val elementEnd = element.completeEnd(end) ?: return YMatroskaDolbyVisionMetadataResult.Invalid
            if (element.id == ID_TRACK_ENTRY) {
                parseTrackEntry(bytes, element.dataStart, elementEnd)?.let {
                    return YMatroskaDolbyVisionMetadataResult.Found(it)
                }
            }
            position = elementEnd
        }
        return YMatroskaDolbyVisionMetadataResult.Absent
    }

    private fun parseTrackEntry(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaDolbyVisionMetadata? {
        var trackType: Long? = null
        var codecId: String? = null
        val mappings = mutableListOf<BlockAdditionMapping>()
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return null
            val elementEnd = element.completeEnd(end) ?: return null
            when (element.id) {
                ID_TRACK_TYPE -> trackType = readUnsigned(bytes, element.dataStart, elementEnd)
                ID_CODEC_ID -> codecId = readAscii(bytes, element.dataStart, elementEnd)
                ID_BLOCK_ADDITION_MAPPING ->
                    parseBlockAdditionMapping(bytes, element.dataStart, elementEnd)?.let(mappings::add)
            }
            position = elementEnd
        }
        if (trackType != TRACK_TYPE_VIDEO) return null
        val resolvedCodecId = codecId ?: return null
        return mappings.firstNotNullOfOrNull { mapping ->
            val effectiveType = mapping.type.takeUnless { it == BLOCK_ADD_TYPE_USE_VALUE } ?: mapping.value
            if (effectiveType !in DOLBY_CONFIGURATION_TYPES) return@firstNotNullOfOrNull null
            val config =
                runCatching { YDolbyVisionConfig.parse(mapping.extraData) }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
            if (!effectiveType.accepts(config.profile) || !resolvedCodecId.accepts(config.codecFamily)) {
                return@firstNotNullOfOrNull null
            }
            YMatroskaDolbyVisionMetadata(
                codecId = resolvedCodecId,
                blockAddIdType = effectiveType,
                config = config,
            )
        }
    }

    private fun parseBlockAdditionMapping(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): BlockAdditionMapping? {
        var value = 0L
        var type = 0L
        var extraData: ByteArray? = null
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return null
            val elementEnd = element.completeEnd(end) ?: return null
            when (element.id) {
                ID_BLOCK_ADD_ID_VALUE -> value = readUnsigned(bytes, element.dataStart, elementEnd) ?: return null
                ID_BLOCK_ADD_ID_TYPE -> type = readUnsigned(bytes, element.dataStart, elementEnd) ?: return null
                ID_BLOCK_ADD_ID_EXTRA_DATA -> extraData = bytes.copyOfRange(element.dataStart, elementEnd)
            }
            position = elementEnd
        }
        return BlockAdditionMapping(value = value, type = type, extraData = extraData ?: return null)
    }
}

/** Track type and CodecID of one Matroska TrackEntry, read from the header without demuxing. */
internal data class YMatroskaTrackCodec(
    val trackType: Long,
    val codecId: String,
) {
    val audio: Boolean get() = trackType == TRACK_TYPE_AUDIO
}

/** Result of a bounded read of every TrackEntry's CodecID. */
internal sealed interface YMatroskaTrackCodecResult {
    data class Found(
        val tracks: List<YMatroskaTrackCodec>,
    ) : YMatroskaTrackCodecResult

    /** More source bytes are required before the Tracks element is complete. */
    data object Truncated : YMatroskaTrackCodecResult

    /** The supplied bytes do not begin with a valid Matroska/EBML document. */
    data object Invalid : YMatroskaTrackCodecResult
}

/**
 * Lists what the container itself declares when MediaExtractor exposes fewer tracks than the
 * server does. It is the only way a diagnostics bundle can say "A_TRUEHD was hidden" rather than
 * "an audio track was hidden", and it reads the same bounded header prefix as the Dolby parser.
 */
internal object YMatroskaTrackCodecParser {
    fun parse(bytes: ByteArray): YMatroskaTrackCodecResult {
        if (bytes.isEmpty()) return YMatroskaTrackCodecResult.Truncated
        val ebml = readElement(bytes, 0) ?: return YMatroskaTrackCodecResult.Truncated
        if (ebml.id != ID_EBML) return YMatroskaTrackCodecResult.Invalid
        var position = ebml.completeEnd(bytes.size) ?: return YMatroskaTrackCodecResult.Truncated
        while (position < bytes.size) {
            val element = readElement(bytes, position) ?: return YMatroskaTrackCodecResult.Truncated
            if (element.id == ID_SEGMENT) {
                return parseSegment(bytes, element.dataStart, element.availableEnd(bytes.size))
            }
            position = element.completeEnd(bytes.size) ?: return YMatroskaTrackCodecResult.Truncated
        }
        return YMatroskaTrackCodecResult.Truncated
    }

    private fun parseSegment(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaTrackCodecResult {
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return YMatroskaTrackCodecResult.Truncated
            when (element.id) {
                ID_TRACKS -> {
                    val tracksEnd = element.completeEnd(end) ?: return YMatroskaTrackCodecResult.Truncated
                    return parseTracks(bytes, element.dataStart, tracksEnd)
                }
                ID_CLUSTER -> return YMatroskaTrackCodecResult.Truncated
            }
            position = element.completeEnd(end) ?: return YMatroskaTrackCodecResult.Truncated
        }
        return YMatroskaTrackCodecResult.Truncated
    }

    private fun parseTracks(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaTrackCodecResult {
        val tracks = mutableListOf<YMatroskaTrackCodec>()
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return YMatroskaTrackCodecResult.Invalid
            val elementEnd = element.completeEnd(end) ?: return YMatroskaTrackCodecResult.Invalid
            if (element.id == ID_TRACK_ENTRY) {
                parseTrackCodec(bytes, element.dataStart, elementEnd)?.let(tracks::add)
            }
            position = elementEnd
        }
        return YMatroskaTrackCodecResult.Found(tracks)
    }

    private fun parseTrackCodec(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): YMatroskaTrackCodec? {
        var trackType: Long? = null
        var codecId: String? = null
        var position = start
        while (position < end) {
            val element = readElement(bytes, position) ?: return null
            val elementEnd = element.completeEnd(end) ?: return null
            when (element.id) {
                ID_TRACK_TYPE -> trackType = readUnsigned(bytes, element.dataStart, elementEnd)
                ID_CODEC_ID -> codecId = readAscii(bytes, element.dataStart, elementEnd)
            }
            position = elementEnd
        }
        return YMatroskaTrackCodec(
            trackType = trackType ?: return null,
            codecId = codecId ?: return null,
        )
    }
}

private data class EbmlElement(
    val id: Long,
    val dataStart: Int,
    val size: Long?,
) {
    fun availableEnd(limit: Int): Int =
        size
            ?.let { declared -> (dataStart.toLong() + declared).coerceAtMost(limit.toLong()).toInt() }
            ?: limit

    fun completeEnd(limit: Int): Int? {
        val declared = size ?: return null
        if (declared > Int.MAX_VALUE || dataStart.toLong() + declared > limit.toLong()) return null
        return dataStart + declared.toInt()
    }
}

private data class EbmlVint(
    val value: Long,
    val length: Int,
    val unknown: Boolean = false,
)

private data class BlockAdditionMapping(
    val value: Long,
    val type: Long,
    val extraData: ByteArray,
)

private fun readElement(
    bytes: ByteArray,
    offset: Int,
): EbmlElement? {
    val id = readVint(bytes, offset, removeMarker = false, maximumLength = 4) ?: return null
    val size = readVint(bytes, offset + id.length, removeMarker = true, maximumLength = 8) ?: return null
    val dataStart = offset + id.length + size.length
    if (dataStart > bytes.size) return null
    return EbmlElement(id = id.value, dataStart = dataStart, size = size.value.takeUnless { size.unknown })
}

private fun readVint(
    bytes: ByteArray,
    offset: Int,
    removeMarker: Boolean,
    maximumLength: Int,
): EbmlVint? {
    if (offset !in bytes.indices) return null
    val first = bytes[offset].toInt() and 0xff
    if (first == 0) return null
    val length = Integer.numberOfLeadingZeros(first) - 23
    if (length !in 1..maximumLength || offset > bytes.size - length) return null
    val marker = 1 shl (8 - length)
    var value = if (removeMarker) (first and (marker - 1)).toLong() else first.toLong()
    for (index in 1 until length) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xffL)
    }
    val unknown = removeMarker && value == (1L shl (7 * length)) - 1L
    return EbmlVint(value = value, length = length, unknown = unknown)
}

private fun readUnsigned(
    bytes: ByteArray,
    start: Int,
    end: Int,
): Long? {
    if (start >= end || end - start > 8) return null
    var value = 0L
    for (index in start until end) value = (value shl 8) or (bytes[index].toLong() and 0xffL)
    return value
}

private fun readAscii(
    bytes: ByteArray,
    start: Int,
    end: Int,
): String? {
    if (start >= end || end - start > MAX_CODEC_ID_BYTES) return null
    if ((start until end).any { index -> bytes[index].toInt() !in 0x20..0x7e }) return null
    return bytes.copyOfRange(start, end).decodeToString()
}

private fun Long.accepts(profile: Int): Boolean =
    when (this) {
        BLOCK_ADD_TYPE_DVCC -> profile in 0..7
        BLOCK_ADD_TYPE_DVVC -> profile in 8..10 || profile == 20
        BLOCK_ADD_TYPE_DVWC -> profile in 11..19
        else -> false
    }

private fun String.accepts(family: YDolbyVisionCodecFamily): Boolean =
    when (family) {
        YDolbyVisionCodecFamily.Hevc -> this == "V_MPEGH/ISO/HEVC"
        YDolbyVisionCodecFamily.Avc -> this == "V_MPEG4/ISO/AVC"
        YDolbyVisionCodecFamily.Av1 -> this == "V_AV1"
        YDolbyVisionCodecFamily.Unknown -> false
    }

private const val ID_EBML = 0x1A45DFA3L
private const val ID_SEGMENT = 0x18538067L
private const val ID_CLUSTER = 0x1F43B675L
private const val ID_TRACKS = 0x1654AE6BL
private const val ID_TRACK_ENTRY = 0xAEL
private const val ID_TRACK_TYPE = 0x83L
private const val ID_CODEC_ID = 0x86L
private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
private const val ID_BLOCK_ADD_ID_VALUE = 0x41F0L
private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL
private const val TRACK_TYPE_VIDEO = 1L
private const val TRACK_TYPE_AUDIO = 2L
private const val BLOCK_ADD_TYPE_USE_VALUE = 0L
private const val BLOCK_ADD_TYPE_DVCC = 0x64766343L
private const val BLOCK_ADD_TYPE_DVVC = 0x64767643L
private const val BLOCK_ADD_TYPE_DVWC = 0x64767743L
private const val MAX_CODEC_ID_BYTES = 64
private val DOLBY_CONFIGURATION_TYPES =
    setOf(BLOCK_ADD_TYPE_DVCC, BLOCK_ADD_TYPE_DVVC, BLOCK_ADD_TYPE_DVWC)
