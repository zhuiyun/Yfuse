package com.yfuse.core2.bitstream

/** Parsed AVCDecoderConfigurationRecord (`avcC`). */
data class YAvcConfiguration(
    val lengthBytes: Int,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
) {
    init {
        require(lengthBytes in 1..4)
        require(sps.isNotEmpty()) { "AVC configuration contains no SPS" }
        require(pps.isNotEmpty()) { "AVC configuration contains no PPS" }
    }

    val samplePacking: YSamplePacking = YSamplePacking.LengthPrefixed(lengthBytes)

    fun csd0AnnexB(): ByteArray = sps.joinAnnexB()

    fun csd1AnnexB(): ByteArray = pps.joinAnnexB()
}

/** Parsed HEVCDecoderConfigurationRecord (`hvcC`). */
data class YHevcConfiguration(
    val lengthBytes: Int,
    val vps: List<ByteArray>,
    val sps: List<ByteArray>,
    val pps: List<ByteArray>,
) {
    init {
        require(lengthBytes in 1..4)
        require(sps.isNotEmpty()) { "HEVC configuration contains no SPS" }
        require(pps.isNotEmpty()) { "HEVC configuration contains no PPS" }
    }

    val samplePacking: YSamplePacking = YSamplePacking.LengthPrefixed(lengthBytes)

    /** Android HEVC decoders conventionally receive VPS/SPS/PPS together in csd-0. */
    fun csd0AnnexB(): ByteArray = (vps + sps + pps).joinAnnexB()
}

object YCodecConfiguration {
    fun parseAvcC(data: ByteArray): YAvcConfiguration {
        require(data.size >= AVC_MIN_BYTES && data[0].u8() == 1) { "Invalid avcC configuration" }
        val lengthBytes = (data[4].u8() and 0x03) + 1
        var cursor = 5
        val spsCount = data[cursor++].u8() and 0x1f
        val sps = mutableListOf<ByteArray>()
        repeat(spsCount) {
            val length = data.readU16(cursor)
            cursor += 2
            sps += data.readBytes(cursor, length)
            cursor += length
        }
        require(cursor < data.size) { "avcC configuration is missing PPS count" }
        val ppsCount = data[cursor++].u8()
        val pps = mutableListOf<ByteArray>()
        repeat(ppsCount) {
            val length = data.readU16(cursor)
            cursor += 2
            pps += data.readBytes(cursor, length)
            cursor += length
        }
        return YAvcConfiguration(
            lengthBytes = lengthBytes,
            sps = sps,
            pps = pps,
        )
    }

    fun parseHvcC(data: ByteArray): YHevcConfiguration {
        require(data.size >= HEVC_ARRAYS_OFFSET && data[0].u8() == 1) { "Invalid hvcC configuration" }
        val lengthBytes = (data[21].u8() and 0x03) + 1
        val arrayCount = data[22].u8()
        var cursor = HEVC_ARRAYS_OFFSET
        val vps = mutableListOf<ByteArray>()
        val sps = mutableListOf<ByteArray>()
        val pps = mutableListOf<ByteArray>()
        repeat(arrayCount) {
            require(cursor + 3 <= data.size) { "Truncated hvcC NAL array" }
            val nalType = data[cursor++].u8() and 0x3f
            val nalCount = data.readU16(cursor)
            cursor += 2
            repeat(nalCount) {
                val length = data.readU16(cursor)
                cursor += 2
                val nal = data.readBytes(cursor, length)
                cursor += length
                when (nalType) {
                    HEVC_VPS -> vps += nal
                    HEVC_SPS -> sps += nal
                    HEVC_PPS -> pps += nal
                }
            }
        }
        return YHevcConfiguration(
            lengthBytes = lengthBytes,
            vps = vps,
            sps = sps,
            pps = pps,
        )
    }
}

private fun ByteArray.readU16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Truncated codec configuration length" }
    return (this[offset].u8() shl 8) or this[offset + 1].u8()
}

private fun ByteArray.readBytes(
    offset: Int,
    length: Int,
): ByteArray {
    require(length > 0) { "Codec configuration NAL length must be positive" }
    require(offset >= 0 && offset + length <= size) { "Codec configuration NAL exceeds record boundary" }
    return copyOfRange(offset, offset + length)
}

private fun Byte.u8(): Int = toInt() and 0xff

private fun List<ByteArray>.joinAnnexB(): ByteArray {
    val total = sumOf { ANNEX_B_START.size + it.size }
    val output = ByteArray(total)
    var cursor = 0
    forEach { nal ->
        ANNEX_B_START.copyInto(output, destinationOffset = cursor)
        cursor += ANNEX_B_START.size
        nal.copyInto(output, destinationOffset = cursor)
        cursor += nal.size
    }
    return output
}

private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
private const val AVC_MIN_BYTES = 7
private const val HEVC_ARRAYS_OFFSET = 23
private const val HEVC_VPS = 32
private const val HEVC_SPS = 33
private const val HEVC_PPS = 34
