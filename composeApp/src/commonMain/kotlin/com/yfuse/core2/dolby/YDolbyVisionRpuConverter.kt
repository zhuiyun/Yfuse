package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YNalUnitSpan
import com.yfuse.core2.bitstream.YSamplePacking
import java.io.ByteArrayOutputStream

/**
 * Converts a Dolby Vision Profile 7 HEVC access unit (MEL or FEL) into a Profile 8.1 single-layer
 * HEVC access unit (`dvhe.08`, HDR10 base + converted RPU NAL 62, with EL NAL 63 stripped).
 *
 * - For MEL RPUs (`disable_residual_flag == 1` and `el_spatial_resampling_filter_flag == 0`), the
 *   existing RPU payload is already Profile 8.1 compliant once its MPEG-2 CRC-32 is verified.
 * - For FEL RPUs (`disable_residual_flag == 0` or `el_spatial_resampling_filter_flag == 1`), the
 *   header flags are rewritten to Profile 8.1, the NLQ residual section is removed from the bit
 *   stream, `vdr_dm_data` dynamic metadata is preserved bit-for-bit, MPEG-2 CRC-32 is recomputed,
 *   and H.265 `0x000003` emulation-prevention bytes are restored.
 * - If an access unit carries a malformed or unsupported exotic RPU, only that RPU NAL is dropped
 *   so the underlying 10-bit HDR10 base frame still decodes without failing playback.
 */
fun convertDolbyVisionProfile7To8Sample(
    data: ByteArray,
    packing: YSamplePacking,
    outputPacking: YSamplePacking = YSamplePacking.AnnexB,
): ByteArray {
    val units = YBitstream.scan(data, YNalCodec.H265, packing)
    require(units.isNotEmpty()) { "Dolby Vision HEVC access unit contains no NAL units" }
    val convertedUnits = ArrayList<ByteArray>(units.size)
    for (unit in units) {
        if (unit.type == DOVI_EL_NAL) continue
        if (unit.type == DOVI_RPU_NAL) {
            val rawNal = data.copyOfRange(unit.offset, unit.offset + unit.length)
            convertDolbyVisionProfile7RpuNalTo81(rawNal)?.let(convertedUnits::add)
            continue
        }
        if (unit.isBaseLayerHevcNal(data)) {
            convertedUnits += data.copyOfRange(unit.offset, unit.offset + unit.length)
        }
    }
    if (convertedUnits.isEmpty()) return ByteArray(0)
    return packRawNals(convertedUnits, outputPacking)
}

/** Converts one raw HEVC `UNSPEC62` RPU NAL (without start code) from Profile 7 to Profile 8.1. */
fun convertDolbyVisionProfile7RpuNalTo81(rawNal: ByteArray): ByteArray? {
    // 2-byte HEVC NAL header + 0x19 RPU prefix + payload + 4-byte CRC32 + 0x80 trailing byte.
    if (rawNal.size < 9 || (rawNal[2].toInt() and 0xFF) != RPU_NAL_PREFIX) return null
    val rbsp = unescapeHevcRbsp(rawNal, startOffset = 3)
    if (rbsp.size < 6) return null
    val trailingIndex = rbsp.lastIndex
    if ((rbsp[trailingIndex].toInt() and 0xFF) != RBSP_TRAILING_BYTE) return null
    val crcOffset = trailingIndex - 4
    val expectedCrc = readBigEndianInt(rbsp, crcOffset)
    val actualCrc = doviMpeg2Crc32(rbsp, 0, crcOffset)
    if (expectedCrc != actualCrc) return null

    val rewrittenPayload = rewriteProfile7RpuPayload(rbsp, payloadByteCount = crcOffset) ?: return null
    if (rewrittenPayload === rbsp) return rawNal

    val newCrc = doviMpeg2Crc32(rewrittenPayload, 0, rewrittenPayload.size)
    val fullRbsp = ByteArray(rewrittenPayload.size + 5)
    rewrittenPayload.copyInto(fullRbsp, 0)
    writeBigEndianInt(fullRbsp, rewrittenPayload.size, newCrc)
    fullRbsp[fullRbsp.lastIndex] = RBSP_TRAILING_BYTE.toByte()

    val escaped = escapeHevcRbsp(fullRbsp)
    val out = ByteArray(3 + escaped.size)
    out[0] = rawNal[0]
    out[1] = rawNal[1]
    out[2] = RPU_NAL_PREFIX.toByte()
    escaped.copyInto(out, 3)
    return out
}

private fun rewriteProfile7RpuPayload(
    rbsp: ByteArray,
    payloadByteCount: Int,
): ByteArray? {
    val totalBits = payloadByteCount * 8
    val reader = RpuBitReader(rbsp, totalBits)
    val writer = RpuBitWriter(totalBits)

    val rpuType = reader.readBits(6) ?: return null
    if (rpuType != 2) return null
    writer.writeBits(rpuType, 6)

    val rpuFormat = reader.readBits(11) ?: return null
    if ((rpuFormat and 0x700) != 0) return null
    writer.writeBits(rpuFormat, 11)

    val vdrRpuProfile = reader.readBits(4) ?: return null
    val vdrRpuLevel = reader.readBits(4) ?: return null
    // Both Profile 7 and Profile 8 use vdr_rpu_profile = 1.
    writer.writeBits(if (vdrRpuProfile == 1) 1 else return null, 4)
    writer.writeBits(vdrRpuLevel, 4)

    val seqInfoPresent = reader.readBit() ?: return null
    writer.writeBit(seqInfoPresent)
    if (seqInfoPresent == 0) {
        // Inter-frame RPU referencing previous sequence header: if no NLQ follows, keep as-is.
        return rbsp
    }

    val chromaFilterFlag = reader.readBit() ?: return null
    writer.writeBit(chromaFilterFlag)
    val coefDataType = reader.readBits(2) ?: return null
    writer.writeBits(coefDataType, 2)
    val coefLog2Denom =
        when (coefDataType) {
            0 -> {
                val denom = reader.readUe() ?: return null
                if (denom > 32) return null
                writer.writeUe(denom)
                denom
            }
            1 -> 23
            else -> return null
        }
    val normalizedIdc = reader.readBits(2) ?: return null
    writer.writeBits(normalizedIdc, 2)
    val blFullRange = reader.readBit() ?: return null
    writer.writeBit(blFullRange)

    val blBitDepthMinus8 = reader.readUe() ?: return null
    val elBitDepthRaw = reader.readUe() ?: return null
    val elBitDepthMinus8 = elBitDepthRaw and 0xFF
    val vdrBitDepthMinus8 = reader.readUe() ?: return null
    if (blBitDepthMinus8 > 8 || elBitDepthMinus8 > 8 || vdrBitDepthMinus8 > 8) return null
    writer.writeUe(blBitDepthMinus8)
    // Profile 8.1 sets el_bit_depth_minus8 equal to bl_bit_depth_minus8 (2 for 10-bit) with no EL ext_mapping_idc.
    writer.writeUe(blBitDepthMinus8)
    writer.writeUe(vdrBitDepthMinus8)

    val spatialResamplingFlag = reader.readBit() ?: return null
    writer.writeBit(spatialResamplingFlag)
    val reservedZero3 = reader.readBits(3) ?: return null
    writer.writeBits(reservedZero3, 3)
    val elSpatialResamplingFlag = reader.readBit() ?: return null
    val disableResidualFlag = reader.readBit() ?: return null

    // MEL Profile 7 with matching 10-bit depth and no residual/resampling is already Profile 8.1 compliant.
    if (elSpatialResamplingFlag == 0 && disableResidualFlag == 1 && elBitDepthRaw == blBitDepthMinus8) {
        return rbsp
    }

    // Rewrite to Profile 8.1: no EL spatial resampling, residual disabled.
    writer.writeBit(0)
    writer.writeBit(1)

    val vdrDmMetadataPresentFlag = reader.readBit() ?: return null
    writer.writeBit(vdrDmMetadataPresentFlag)
    val usePrevVdrRpuFlag = reader.readBit() ?: return null
    writer.writeBit(usePrevVdrRpuFlag)

    if (usePrevVdrRpuFlag == 1) {
        val prevRpuId = reader.readUe() ?: return null
        writer.writeUe(prevRpuId)
    } else {
        val rpuId = reader.readUe() ?: return null
        val mappingColorSpace = reader.readUe() ?: return null
        val mappingChromaFormatIdc = reader.readUe() ?: return null
        writer.writeUe(rpuId)
        writer.writeUe(mappingColorSpace)
        writer.writeUe(mappingChromaFormatIdc)

        val blBitDepth = blBitDepthMinus8 + 8
        val elBitDepth = elBitDepthMinus8 + 8
        val numPivotsMinus2 = IntArray(3)
        for (cmp in 0 until 3) {
            val pivots = reader.readUe() ?: return null
            if (pivots > 16) return null
            numPivotsMinus2[cmp] = pivots
            writer.writeUe(pivots)
            repeat(pivots + 2) {
                val predPivot = reader.readBits(blBitDepth) ?: return null
                writer.writeBits(predPivot, blBitDepth)
            }
        }

        // In vdr_rpu_data_header(), NLQ header fields sit right after pred_pivot_value when disable_residual_flag == 0.
        var nlqMethodIdc = 0
        var nlqNumPivotsMinus2 = 0
        if (disableResidualFlag == 0) {
            nlqMethodIdc = reader.readBits(3) ?: return null
            nlqNumPivotsMinus2 = reader.readUe() ?: return null
            if (nlqMethodIdc != 0 || nlqNumPivotsMinus2 > 8) return null
            repeat(nlqNumPivotsMinus2 + 2) {
                reader.readBits(blBitDepth) ?: return null // nlq_pred_pivot_value (omitted in Profile 8.1)
            }
        }

        val numXPartitionsMinus1 = reader.readUe() ?: return null
        val numYPartitionsMinus1 = reader.readUe() ?: return null
        val overshootRangeIdc = reader.readUe() ?: return null
        val partitionsLong = (numXPartitionsMinus1 + 1L) * (numYPartitionsMinus1 + 1L)
        if (partitionsLong <= 0L || partitionsLong > 64L) return null
        writer.writeUe(numXPartitionsMinus1)
        writer.writeUe(numYPartitionsMinus1)
        writer.writeUe(overshootRangeIdc)
        val partitions = partitionsLong.toInt()

        // rpu_data_mapping() per spatial partition.
        repeat(partitions) {
            for (cmp in 0 until 3) {
                for (piece in 0 until numPivotsMinus2[cmp] + 1) {
                    val mappingIdc = reader.readUe() ?: return null
                    writer.writeUe(mappingIdc)
                    when (mappingIdc) {
                        0 -> { // MAPPING_POLYNOMIAL
                            val polyOrderMinus1 = reader.readUe() ?: return null
                            if (polyOrderMinus1 > 1) return null
                            writer.writeUe(polyOrderMinus1)
                            val linearInterpFlag =
                                if (polyOrderMinus1 == 0) {
                                    val flag = reader.readBit() ?: return null
                                    writer.writeBit(flag)
                                    flag
                                } else {
                                    0
                                }
                            if (polyOrderMinus1 == 0 && linearInterpFlag == 1) {
                                copySignedOrLinearCoefficient(
                                    reader,
                                    writer,
                                    coefDataType,
                                    coefLog2Denom,
                                    signed = false,
                                )
                                    ?: return null
                                copySignedOrLinearCoefficient(
                                    reader,
                                    writer,
                                    coefDataType,
                                    coefLog2Denom,
                                    signed = false,
                                )
                                    ?: return null
                            } else {
                                repeat(polyOrderMinus1 + 2) {
                                    copySignedOrLinearCoefficient(
                                        reader,
                                        writer,
                                        coefDataType,
                                        coefLog2Denom,
                                        signed = true,
                                    )
                                        ?: return null
                                }
                            }
                        }
                        1 -> { // MAPPING_MMR
                            val mmrOrderMinus1 = reader.readBits(2) ?: return null
                            if (mmrOrderMinus1 > 2) return null
                            writer.writeBits(mmrOrderMinus1, 2)
                            copySignedOrLinearCoefficient(reader, writer, coefDataType, coefLog2Denom, signed = true)
                                ?: return null
                            val termCounts = intArrayOf(7, 14, 22)
                            var totalTerms = 0
                            for (order in 0..mmrOrderMinus1) totalTerms += termCounts[order]
                            repeat(totalTerms) {
                                copySignedOrLinearCoefficient(
                                    reader,
                                    writer,
                                    coefDataType,
                                    coefLog2Denom,
                                    signed = true,
                                )
                                    ?: return null
                            }
                        }
                        else -> return null
                    }
                }
            }
        }

        // Skip rpu_data_nlq() when the source Profile 7 FEL stream carried residual NLQ data.
        if (disableResidualFlag == 0) {
            repeat(partitions) {
                repeat(nlqNumPivotsMinus2 + 1) {
                    for (cmp in 0 until 3) {
                        reader.readBits(elBitDepth) ?: return null // nlq_offset
                        skipUnsignedCoefficient(reader, coefDataType, coefLog2Denom) ?: return null // vdr_in_max
                        if (nlqMethodIdc == 0) {
                            skipUnsignedCoefficient(reader, coefDataType, coefLog2Denom)
                                ?: return null // linear_deadzone_slope
                            skipUnsignedCoefficient(reader, coefDataType, coefLog2Denom)
                                ?: return null // linear_deadzone_threshold
                        }
                    }
                }
            }
        }
    }

    if (vdrDmMetadataPresentFlag == 1) {
        val affectedDmMetadataId = reader.readUe() ?: return null
        val currentDmMetadataId = reader.readUe() ?: return null
        val sceneRefreshFlag = reader.readUe() ?: return null
        writer.writeUe(affectedDmMetadataId)
        writer.writeUe(currentDmMetadataId)
        writer.writeUe(sceneRefreshFlag)
        repeat(9) {
            val matrixCoef = reader.readBits(16) ?: return null
            writer.writeBits(matrixCoef, 16)
        }
        repeat(3) {
            val offsetCoef = reader.readBits(32) ?: return null
            writer.writeBits(offsetCoef, 32)
        }
        repeat(9) {
            val rgbToLmsCoef = reader.readBits(16) ?: return null
            writer.writeBits(rgbToLmsCoef, 16)
        }
        val signalEotf = reader.readBits(16) ?: return null
        val signalEotfParam0 = reader.readBits(16) ?: return null
        val signalEotfParam1 = reader.readBits(16) ?: return null
        val signalEotfParam2 = reader.readBits(32) ?: return null
        val signalBitDepth = reader.readBits(5) ?: return null
        val signalColorSpace = reader.readBits(2) ?: return null
        val signalChromaFormat = reader.readBits(2) ?: return null
        val signalFullRangeFlag = reader.readBits(2) ?: return null
        val sourceMinPq = reader.readBits(12) ?: return null
        val sourceMaxPq = reader.readBits(12) ?: return null
        val sourceDiagonal = reader.readBits(10) ?: return null
        writer.writeBits(signalEotf, 16)
        writer.writeBits(signalEotfParam0, 16)
        writer.writeBits(signalEotfParam1, 16)
        writer.writeBits(signalEotfParam2, 32)
        writer.writeBits(signalBitDepth, 5)
        writer.writeBits(signalColorSpace, 2)
        writer.writeBits(signalChromaFormat, 2)
        writer.writeBits(signalFullRangeFlag, 2)
        writer.writeBits(sourceMinPq, 12)
        writer.writeBits(sourceMaxPq, 12)
        writer.writeBits(sourceDiagonal, 10)

        val numExtBlocks = reader.readUe() ?: return null
        if (numExtBlocks > 256) return null
        writer.writeUe(numExtBlocks)
        if (numExtBlocks > 0) {
            if (!reader.alignToZeroByte()) return null
            writer.alignWithZeros()
            repeat(numExtBlocks) {
                val extBlockLength = reader.readUe() ?: return null
                val extBlockLevel = reader.readBits(8) ?: return null
                if (extBlockLength > 4096) return null
                writer.writeUe(extBlockLength)
                writer.writeBits(extBlockLevel, 8)
                repeat(extBlockLength) {
                    val b = reader.readBits(8) ?: return null
                    writer.writeBits(b, 8)
                }
            }
        }
    }

    // Remaining padding bits before rpu_data_crc32 must all be 0.
    while (reader.remainingBits > 0) {
        if ((reader.readBit() ?: return null) != 0) return null
    }
    writer.alignWithZeros()
    return writer.toByteArray()
}

private fun copySignedOrLinearCoefficient(
    reader: RpuBitReader,
    writer: RpuBitWriter,
    coefDataType: Int,
    coefLog2Denom: Int,
    signed: Boolean,
): Unit? {
    if (coefDataType == 0) {
        if (signed) {
            val intPart = reader.readSe() ?: return null
            writer.writeSe(intPart)
        } else {
            val intPart = reader.readUe() ?: return null
            writer.writeUe(intPart)
        }
        if (coefLog2Denom > 0) {
            val frac = reader.readBits(coefLog2Denom) ?: return null
            writer.writeBits(frac, coefLog2Denom)
        }
    } else {
        val fp = reader.readBits(32) ?: return null
        writer.writeBits(fp, 32)
    }
    return Unit
}

private fun skipUnsignedCoefficient(
    reader: RpuBitReader,
    coefDataType: Int,
    coefLog2Denom: Int,
): Unit? {
    if (coefDataType == 0) {
        reader.readUe() ?: return null
        if (coefLog2Denom > 0) reader.readBits(coefLog2Denom) ?: return null
    } else {
        reader.readBits(32) ?: return null
    }
    return Unit
}

internal fun doviMpeg2Crc32(
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
): Int {
    var crc = -1 // 0xFFFF_FFFF
    for (index in offset until offset + length) {
        crc = crc xor ((data[index].toInt() and 0xFF) shl 24)
        repeat(8) {
            crc = if (crc < 0) (crc shl 1) xor MPEG2_CRC_POLY else (crc shl 1)
        }
    }
    return crc
}

private fun unescapeHevcRbsp(
    nal: ByteArray,
    startOffset: Int,
): ByteArray {
    val out = ByteArrayOutputStream(nal.size - startOffset)
    var zeros = 0
    for (index in startOffset until nal.size) {
        val value = nal[index].toInt() and 0xFF
        if (zeros == 2 && value == 0x03) {
            zeros = 0
            continue
        }
        out.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

private fun escapeHevcRbsp(rbsp: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(rbsp.size + 16)
    var zeros = 0
    for (byte in rbsp) {
        val value = byte.toInt() and 0xFF
        if (zeros == 2 && value <= 0x03) {
            out.write(0x03)
            zeros = 0
        }
        out.write(value)
        zeros = if (value == 0) zeros + 1 else 0
    }
    return out.toByteArray()
}

private fun YNalUnitSpan.isBaseLayerHevcNal(data: ByteArray): Boolean {
    if (length < 2) return false
    val first = data[offset].toInt()
    val second = data[offset + 1].toInt()
    return first and 1 == 0 && second and 0xF8 == 0
}

private fun packRawNals(
    nals: List<ByteArray>,
    packing: YSamplePacking,
): ByteArray {
    val prefixBytes =
        when (packing) {
            YSamplePacking.AnnexB -> 4
            is YSamplePacking.LengthPrefixed -> packing.lengthBytes
        }
    val total = nals.sumOf { prefixBytes + it.size }
    val out = ByteArray(total)
    var pos = 0
    for (nal in nals) {
        when (packing) {
            YSamplePacking.AnnexB -> {
                out[pos] = 0
                out[pos + 1] = 0
                out[pos + 2] = 0
                out[pos + 3] = 1
                pos += 4
            }
            is YSamplePacking.LengthPrefixed -> {
                for (shift in packing.lengthBytes - 1 downTo 0) {
                    out[pos++] = ((nal.size ushr (shift * 8)) and 0xFF).toByte()
                }
            }
        }
        nal.copyInto(out, pos)
        pos += nal.size
    }
    return out
}

private fun readBigEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun writeBigEndianInt(
    bytes: ByteArray,
    offset: Int,
    value: Int,
) {
    bytes[offset] = (value ushr 24).toByte()
    bytes[offset + 1] = (value ushr 16).toByte()
    bytes[offset + 2] = (value ushr 8).toByte()
    bytes[offset + 3] = value.toByte()
}

private class RpuBitReader(
    private val data: ByteArray,
    private val limitBits: Int,
) {
    private var bitPosition = 0

    val remainingBits: Int get() = limitBits - bitPosition

    fun readBit(): Int? {
        if (bitPosition >= limitBits) return null
        val byteIndex = bitPosition ushr 3
        val shift = 7 - (bitPosition and 7)
        bitPosition++
        return (data[byteIndex].toInt() ushr shift) and 1
    }

    fun readBits(count: Int): Int? {
        if (count == 0) return 0
        if (count < 0 || count > 32 || remainingBits < count) return null
        var value = 0
        repeat(count) {
            value = (value shl 1) or (readBit() ?: return null)
        }
        return value
    }

    fun readUe(): Int? {
        var leadingZeros = 0
        while (true) {
            val bit = readBit() ?: return null
            if (bit == 1) break
            leadingZeros++
            if (leadingZeros > 30) return null
        }
        if (leadingZeros == 0) return 0
        val suffix = readBits(leadingZeros) ?: return null
        return ((1 shl leadingZeros) - 1) + suffix
    }

    fun readSe(): Int? {
        val codeNum = readUe() ?: return null
        val magnitude = (codeNum + 1) ushr 1
        return if ((codeNum and 1) != 0) magnitude else -magnitude
    }

    fun alignToZeroByte(): Boolean {
        while ((bitPosition and 7) != 0) {
            val pad = readBit() ?: return false
            if (pad != 0) return false
        }
        return true
    }
}

private class RpuBitWriter(
    initialBitCapacity: Int,
) {
    private var buffer = ByteArray(((initialBitCapacity + 7) ushr 3).coerceAtLeast(64))
    private var bitPosition = 0

    fun writeBit(bit: Int) {
        val byteIndex = bitPosition ushr 3
        if (byteIndex >= buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        if ((bit and 1) != 0) {
            val shift = 7 - (bitPosition and 7)
            buffer[byteIndex] = (buffer[byteIndex].toInt() or (1 shl shift)).toByte()
        }
        bitPosition++
    }

    fun writeBits(
        value: Int,
        count: Int,
    ) {
        for (shift in count - 1 downTo 0) {
            writeBit((value ushr shift) and 1)
        }
    }

    fun writeUe(value: Int) {
        val code = value + 1
        val bits = 32 - code.countLeadingZeroBits()
        val leadingZeros = bits - 1
        repeat(leadingZeros) { writeBit(0) }
        writeBits(code, bits)
    }

    fun writeSe(value: Int) {
        val codeNum = if (value <= 0) -2 * value else 2 * value - 1
        writeUe(codeNum)
    }

    fun alignWithZeros() {
        while ((bitPosition and 7) != 0) writeBit(0)
    }

    fun toByteArray(): ByteArray {
        alignWithZeros()
        return buffer.copyOf(bitPosition ushr 3)
    }
}

private const val DOVI_RPU_NAL = 62
private const val DOVI_EL_NAL = 63
private const val RPU_NAL_PREFIX = 0x19
private const val RBSP_TRAILING_BYTE = 0x80
private const val MPEG2_CRC_POLY = 0x04C11DB7
