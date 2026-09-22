package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking

/**
 * Produces an HEVC base-layer access unit for explicitly compatible Dolby Vision profiles.
 *
 * RPU (UNSPEC62) and enhancement-layer (UNSPEC63) units are removed and the output is normalized
 * to Annex-B. Callers must still require a Dolby configuration whose compatibility id proves the
 * remaining base is a valid HDR10/HLG/SDR representation; this helper never makes that decision.
 */
fun dolbyVisionHevcBaseLayerSample(
    data: ByteArray,
    packing: YSamplePacking,
): ByteArray {
    val units = YBitstream.scan(data, YNalCodec.H265, packing)
    require(units.isNotEmpty()) { "Dolby Vision HEVC access unit contains no NAL units" }
    val baseUnits = units.filterNot { it.type == DOVI_RPU_NAL || it.type == DOVI_EL_NAL }
    require(baseUnits.isNotEmpty()) { "Dolby Vision access unit contains no base-layer NAL units" }
    val output = ByteArray(baseUnits.sumOf { ANNEX_B_START.size + it.length })
    var cursor = 0
    baseUnits.forEach { unit ->
        ANNEX_B_START.copyInto(output, destinationOffset = cursor)
        cursor += ANNEX_B_START.size
        data.copyInto(
            destination = output,
            destinationOffset = cursor,
            startIndex = unit.offset,
            endIndex = unit.offset + unit.length,
        )
        cursor += unit.length
    }
    return output
}

private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
private const val DOVI_RPU_NAL = 62
private const val DOVI_EL_NAL = 63
