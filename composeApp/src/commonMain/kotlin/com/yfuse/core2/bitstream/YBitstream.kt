package com.yfuse.core2.bitstream

/** Codec-specific NAL type interpretation. */
enum class YNalCodec {
    H264,
    H265,
}

sealed interface YSamplePacking {
    data object AnnexB : YSamplePacking

    data class LengthPrefixed(
        val lengthBytes: Int = 4,
    ) : YSamplePacking {
        init {
            require(lengthBytes in 1..4) { "NAL length field must use 1 to 4 bytes" }
        }
    }
}

data class YNalUnitSpan(
    val offset: Int,
    val length: Int,
    val type: Int,
) {
    init {
        require(offset >= 0)
        require(length > 0)
    }
}

data class YParameterSets(
    val vps: List<ByteArray> = emptyList(),
    val sps: List<ByteArray> = emptyList(),
    val pps: List<ByteArray> = emptyList(),
)

/** Stream-level evidence only; enhancement-layer presence must never be promoted to a FEL claim. */
data class YDolbyVisionNalEvidence(
    val rpuCount: Int,
    val enhancementLayerCount: Int,
) {
    val rpuPresent: Boolean get() = rpuCount > 0
    val enhancementLayerPresent: Boolean get() = enhancementLayerCount > 0
}

/**
 * Container-neutral NAL parser/normalizer.
 *
 * The parser returns spans into the original encoded access unit so the playback path can inspect
 * metadata without copying. Conversion allocates only when the target MediaCodec packing differs
 * from the source container.
 */
object YBitstream {
    fun scan(
        data: ByteArray,
        codec: YNalCodec,
        packing: YSamplePacking,
    ): List<YNalUnitSpan> =
        when (packing) {
            YSamplePacking.AnnexB -> scanAnnexB(data, codec)
            is YSamplePacking.LengthPrefixed -> scanLengthPrefixed(data, codec, packing.lengthBytes)
        }

    fun normalize(
        data: ByteArray,
        codec: YNalCodec,
        from: YSamplePacking,
        to: YSamplePacking,
    ): ByteArray {
        if (from == to) return data
        val units = scan(data, codec, from)
        require(units.isNotEmpty()) { "Encoded access unit contains no NAL units" }
        return when (to) {
            YSamplePacking.AnnexB -> units.toAnnexB(data)
            is YSamplePacking.LengthPrefixed -> units.toLengthPrefixed(data, to.lengthBytes)
        }
    }

    fun parameterSets(
        data: ByteArray,
        codec: YNalCodec,
        packing: YSamplePacking,
    ): YParameterSets {
        val units = scan(data, codec, packing)
        return when (codec) {
            YNalCodec.H264 ->
                YParameterSets(
                    sps = units.filter { it.type == H264_SPS }.map { data.copyOfSpan(it) },
                    pps = units.filter { it.type == H264_PPS }.map { data.copyOfSpan(it) },
                )
            YNalCodec.H265 ->
                YParameterSets(
                    vps = units.filter { it.type == H265_VPS }.map { data.copyOfSpan(it) },
                    sps = units.filter { it.type == H265_SPS }.map { data.copyOfSpan(it) },
                    pps = units.filter { it.type == H265_PPS }.map { data.copyOfSpan(it) },
                )
        }
    }

    /**
     * Dolby Vision HEVC carries RPU and enhancement-layer units in the reserved UNSPEC62/63 NAL
     * types used by the established FFmpeg Dolby-Vision bitstream path. Counts are evidence only:
     * an EL NAL proves an enhancement layer exists, not that a decoder actually composed FEL.
     */
    fun dolbyVisionEvidence(
        data: ByteArray,
        packing: YSamplePacking,
    ): YDolbyVisionNalEvidence {
        val units = scan(data, YNalCodec.H265, packing)
        return YDolbyVisionNalEvidence(
            rpuCount = units.count { it.type == H265_DOLBY_VISION_RPU },
            enhancementLayerCount = units.count { it.type == H265_DOLBY_VISION_EL },
        )
    }

    fun containsDolbyVisionRpu(
        data: ByteArray,
        packing: YSamplePacking,
    ): Boolean = dolbyVisionEvidence(data, packing).rpuPresent

    fun containsDolbyVisionEnhancementLayer(
        data: ByteArray,
        packing: YSamplePacking,
    ): Boolean = dolbyVisionEvidence(data, packing).enhancementLayerPresent
}

private fun scanAnnexB(
    data: ByteArray,
    codec: YNalCodec,
): List<YNalUnitSpan> {
    val result = mutableListOf<YNalUnitSpan>()
    var start = findAnnexBStart(data, 0) ?: return emptyList()
    while (true) {
        val payloadStart = start.payloadStart
        val next = findAnnexBStart(data, payloadStart)
        val payloadEnd = next?.prefixStart ?: data.size
        if (payloadEnd > payloadStart) {
            result +=
                YNalUnitSpan(
                    offset = payloadStart,
                    length = payloadEnd - payloadStart,
                    type = nalType(data, payloadStart, payloadEnd - payloadStart, codec),
                )
        }
        start = next ?: break
    }
    return result
}

private fun scanLengthPrefixed(
    data: ByteArray,
    codec: YNalCodec,
    lengthBytes: Int,
): List<YNalUnitSpan> {
    val result = mutableListOf<YNalUnitSpan>()
    var cursor = 0
    while (cursor < data.size) {
        require(cursor + lengthBytes <= data.size) { "Truncated NAL length field" }
        var length = 0L
        repeat(lengthBytes) { index ->
            length = (length shl 8) or (data[cursor + index].toInt() and 0xff).toLong()
        }
        cursor += lengthBytes
        require(length > 0L && length <= Int.MAX_VALUE.toLong()) { "Invalid NAL length $length" }
        val unitLength = length.toInt()
        require(cursor + unitLength <= data.size) { "NAL length $unitLength exceeds access-unit boundary" }
        result +=
            YNalUnitSpan(
                offset = cursor,
                length = unitLength,
                type = nalType(data, cursor, unitLength, codec),
            )
        cursor += unitLength
    }
    return result
}

private data class AnnexBStart(
    val prefixStart: Int,
    val payloadStart: Int,
)

/**
 * Treats an entire run of 2+ zero bytes followed by 0x01 as the delimiter. This avoids leaking an
 * extra `trailing_zero_8bits` byte into the previous NAL when a stream uses a long Annex-B prefix.
 */
private fun findAnnexBStart(
    data: ByteArray,
    fromIndex: Int,
): AnnexBStart? {
    var zeroRunStart = -1
    var zeroCount = 0
    var index = fromIndex.coerceAtLeast(0)
    while (index < data.size) {
        when (data[index].toInt() and 0xff) {
            0 -> {
                if (zeroCount == 0) zeroRunStart = index
                zeroCount++
            }
            1 -> {
                if (zeroCount >= 2) {
                    return AnnexBStart(
                        prefixStart = zeroRunStart,
                        payloadStart = index + 1,
                    )
                }
                zeroRunStart = -1
                zeroCount = 0
            }
            else -> {
                zeroRunStart = -1
                zeroCount = 0
            }
        }
        index++
    }
    return null
}

private fun nalType(
    data: ByteArray,
    offset: Int,
    length: Int,
    codec: YNalCodec,
): Int {
    require(length > 0 && offset in data.indices)
    val first = data[offset].toInt() and 0xff
    return when (codec) {
        YNalCodec.H264 -> first and 0x1f
        YNalCodec.H265 -> {
            require(length >= 2) { "HEVC NAL unit is shorter than its 2-byte header" }
            (first ushr 1) and 0x3f
        }
    }
}

private fun List<YNalUnitSpan>.toAnnexB(source: ByteArray): ByteArray {
    val total = sumOf { ANNEX_B_PREFIX.size + it.length }
    val output = ByteArray(total)
    var cursor = 0
    forEach { span ->
        ANNEX_B_PREFIX.copyInto(output, destinationOffset = cursor)
        cursor += ANNEX_B_PREFIX.size
        source.copyInto(
            destination = output,
            destinationOffset = cursor,
            startIndex = span.offset,
            endIndex = span.offset + span.length,
        )
        cursor += span.length
    }
    return output
}

private fun List<YNalUnitSpan>.toLengthPrefixed(
    source: ByteArray,
    lengthBytes: Int,
): ByteArray {
    val maxLength = (if (lengthBytes == 4) UINT32_SIZE else 1L shl (lengthBytes * 8)) - 1L
    forEach { require(it.length.toLong() <= maxLength) { "NAL unit is too large for $lengthBytes-byte length field" } }
    val total = sumOf { lengthBytes + it.length }
    val output = ByteArray(total)
    var cursor = 0
    forEach { span ->
        var value = span.length.toLong()
        for (index in lengthBytes - 1 downTo 0) {
            output[cursor + index] = (value and 0xff).toByte()
            value = value ushr 8
        }
        cursor += lengthBytes
        source.copyInto(
            destination = output,
            destinationOffset = cursor,
            startIndex = span.offset,
            endIndex = span.offset + span.length,
        )
        cursor += span.length
    }
    return output
}

private fun ByteArray.copyOfSpan(span: YNalUnitSpan): ByteArray = copyOfRange(span.offset, span.offset + span.length)

private val ANNEX_B_PREFIX = byteArrayOf(0, 0, 0, 1)
private const val H264_SPS = 7
private const val H264_PPS = 8
private const val H265_VPS = 32
private const val H265_SPS = 33
private const val H265_PPS = 34
private const val H265_DOLBY_VISION_RPU = 62
private const val H265_DOLBY_VISION_EL = 63
private const val UINT32_SIZE = 1L shl 32
