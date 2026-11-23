package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.bitstream.pack

/**
 * Produces an HEVC base-layer access unit for explicitly compatible Dolby Vision profiles.
 *
 * MediaCodec accepts Annex B. A decoder initialized with container extradata needs that
 * container's original NAL packing. A packet that contains only enhancement data has no base
 * bytes to enqueue. Callers must verify the source's base-layer compatibility before filtering.
 */
fun dolbyVisionHevcBaseLayerSample(
    data: ByteArray,
    packing: YSamplePacking,
    outputPacking: YSamplePacking = YSamplePacking.AnnexB,
): ByteArray {
    val units = YBitstream.scan(data, YNalCodec.H265, packing)
    require(units.isNotEmpty()) { "Dolby Vision HEVC access unit contains no NAL units" }
    val baseUnits =
        units.filter { unit ->
            val first = data[unit.offset].toInt()
            val second = data[unit.offset + 1].toInt()
            val baseLayer = first and 1 == 0 && second and 0xf8 == 0
            baseLayer && unit.type != DOVI_RPU_NAL && unit.type != DOVI_EL_NAL
        }
    return if (baseUnits.size == units.size && outputPacking == packing) data else baseUnits.pack(data, outputPacking)
}

private const val DOVI_RPU_NAL = 62
private const val DOVI_EL_NAL = 63
