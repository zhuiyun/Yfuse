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

data class YAv1ObuSpan(
    val offset: Int,
    val length: Int,
    val type: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
    val hasSizeField: Boolean,
) {
    init {
        require(offset >= 0 && length > 0)
        require(payloadOffset >= offset && payloadLength >= 0)
        require(payloadOffset + payloadLength <= offset + length)
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
    /** Parses one AV1 low-overhead access unit without treating OBUs as H26x NAL units. */
    fun scanAv1(data: ByteArray): List<YAv1ObuSpan> {
        val result = mutableListOf<YAv1ObuSpan>()
        var cursor = 0
        while (cursor < data.size) {
            val start = cursor
            val header = data[cursor++].toInt() and 0xff
            require(header and AV1_FORBIDDEN_BIT == 0) { "AV1 OBU forbidden bit is set" }
            require(header and AV1_RESERVED_BIT == 0) { "AV1 OBU reserved bit is set" }
            val type = (header ushr AV1_TYPE_SHIFT) and AV1_TYPE_MASK
            val extension = header and AV1_EXTENSION_FLAG != 0
            val hasSize = header and AV1_HAS_SIZE_FIELD != 0
            if (extension) {
                require(cursor < data.size) { "AV1 OBU extension header is truncated" }
                val extensionHeader = data[cursor++].toInt() and 0xff
                require(extensionHeader and AV1_EXTENSION_RESERVED_MASK == 0) {
                    "AV1 OBU extension reserved bits are set"
                }
            }
            val payloadLength =
                if (hasSize) {
                    val decoded = data.readLeb128(cursor)
                    cursor = decoded.nextOffset
                    decoded.value
                } else {
                    data.size - cursor
                }
            require(payloadLength >= 0 && payloadLength <= data.size - cursor) {
                "AV1 OBU payload exceeds access-unit boundary"
            }
            val payloadOffset = cursor
            cursor += payloadLength
            result +=
                YAv1ObuSpan(
                    offset = start,
                    length = cursor - start,
                    type = type,
                    payloadOffset = payloadOffset,
                    payloadLength = payloadLength,
                    hasSizeField = hasSize,
                )
            if (!hasSize) break
        }
        return result
    }

    /** Ensures every AV1 OBU carries an explicit LEB128 payload size for MediaCodec input. */
    fun normalizeAv1LowOverhead(data: ByteArray): ByteArray {
        val units = scanAv1(data)
        require(units.isNotEmpty()) { "AV1 access unit contains no OBUs" }
        if (units.all(YAv1ObuSpan::hasSizeField)) return data
        val output = ArrayList<Byte>(data.size + units.size * 2)
        units.forEach { unit ->
            val originalHeader = data[unit.offset].toInt() and 0xff
            output += (originalHeader or AV1_HAS_SIZE_FIELD).toByte()
            if (originalHeader and AV1_EXTENSION_FLAG != 0) {
                output += data[unit.offset + 1]
            }
            unit.payloadLength.toLeb128().forEach { output.add(it) }
            for (index in unit.payloadOffset until unit.payloadOffset + unit.payloadLength) {
                output += data[index]
            }
        }
        return output.toByteArray()
    }

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

    /** Extracts registered ITU-T T.35 HDR10+ metadata from HEVC prefix/suffix SEI NAL units. */
    fun hdr10PlusItuT35Payload(
        data: ByteArray,
        packing: YSamplePacking,
    ): ByteArray? =
        scan(data, YNalCodec.H265, packing)
            .asSequence()
            .filter { it.type == H265_PREFIX_SEI || it.type == H265_SUFFIX_SEI }
            .mapNotNull { span -> data.registeredItuT35Payload(span) }
            .firstOrNull(::isHdr10PlusPayload)
}

private fun ByteArray.registeredItuT35Payload(span: YNalUnitSpan): ByteArray? {
    if (span.length <= H265_NAL_HEADER_BYTES) return null
    val rbsp =
        copyOfRange(span.offset + H265_NAL_HEADER_BYTES, span.offset + span.length)
            .removeEmulationPreventionBytes()
    var cursor = 0
    while (cursor < rbsp.size) {
        var payloadType = 0
        while (cursor < rbsp.size && rbsp[cursor].toInt() and 0xff == 0xff) {
            payloadType += 0xff
            cursor++
        }
        if (cursor >= rbsp.size) return null
        payloadType += rbsp[cursor++].toInt() and 0xff
        var payloadSize = 0
        while (cursor < rbsp.size && rbsp[cursor].toInt() and 0xff == 0xff) {
            payloadSize += 0xff
            cursor++
        }
        if (cursor >= rbsp.size) return null
        payloadSize += rbsp[cursor++].toInt() and 0xff
        if (payloadSize < 0 || cursor + payloadSize > rbsp.size) return null
        if (payloadType == REGISTERED_ITU_T_T35_PAYLOAD_TYPE) {
            return rbsp.copyOfRange(cursor, cursor + payloadSize)
        }
        cursor += payloadSize
    }
    return null
}

private fun ByteArray.removeEmulationPreventionBytes(): ByteArray {
    val output = ArrayList<Byte>(size)
    var zeroCount = 0
    forEach { value ->
        val unsigned = value.toInt() and 0xff
        if (zeroCount >= 2 && unsigned == 0x03) {
            zeroCount = 2
        } else {
            output += value
            zeroCount = if (unsigned == 0) zeroCount + 1 else 0
        }
    }
    return output.toByteArray()
}

private fun isHdr10PlusPayload(payload: ByteArray): Boolean =
    payload.size >= HDR10_PLUS_IDENTIFIER.size &&
        HDR10_PLUS_IDENTIFIER.indices.all { payload[it] == HDR10_PLUS_IDENTIFIER[it] }

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
private const val H265_PREFIX_SEI = 39
private const val H265_SUFFIX_SEI = 40
private const val H265_DOLBY_VISION_RPU = 62
private const val H265_DOLBY_VISION_EL = 63
private const val H265_NAL_HEADER_BYTES = 2
private const val REGISTERED_ITU_T_T35_PAYLOAD_TYPE = 4
private val HDR10_PLUS_IDENTIFIER = byteArrayOf(0xb5.toByte(), 0x00, 0x3c, 0x00, 0x01, 0x04)
private const val UINT32_SIZE = 1L shl 32
private const val AV1_FORBIDDEN_BIT = 0x80
private const val AV1_TYPE_SHIFT = 3
private const val AV1_TYPE_MASK = 0x0f
private const val AV1_EXTENSION_FLAG = 0x04
private const val AV1_HAS_SIZE_FIELD = 0x02
private const val AV1_RESERVED_BIT = 0x01
private const val AV1_EXTENSION_RESERVED_MASK = 0x07
private const val AV1_MAX_LEB128_BYTES = 8

private data class Leb128Value(
    val value: Int,
    val nextOffset: Int,
)

private fun ByteArray.readLeb128(offset: Int): Leb128Value {
    var cursor = offset
    var value = 0L
    var shift = 0
    repeat(AV1_MAX_LEB128_BYTES) {
        require(cursor < size) { "AV1 OBU size field is truncated" }
        val byte = this[cursor++].toInt() and 0xff
        value = value or ((byte and 0x7f).toLong() shl shift)
        require(value <= Int.MAX_VALUE) { "AV1 OBU size exceeds the supported access-unit limit" }
        if (byte and 0x80 == 0) return Leb128Value(value.toInt(), cursor)
        shift += 7
    }
    error("AV1 OBU size field exceeds $AV1_MAX_LEB128_BYTES bytes")
}

private fun Int.toLeb128(): List<Byte> {
    require(this >= 0)
    var remaining = this
    val output = mutableListOf<Byte>()
    do {
        var byte = remaining and 0x7f
        remaining = remaining ushr 7
        if (remaining != 0) byte = byte or 0x80
        output += byte.toByte()
    } while (remaining != 0)
    return output
}
