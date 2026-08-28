package com.yfuse.core2.dolby

import com.yfuse.core2.bitstream.YBitstream
import com.yfuse.core2.bitstream.YNalCodec
import com.yfuse.core2.bitstream.YSamplePacking
import com.yfuse.core2.demux.YCompressedSample

data class YDolbyVisionLayerAccessUnit(
    val presentationTimeUs: Long,
    val baseLayer: ByteArray,
    val rpu: List<ByteArray>,
    val enhancementLayer: List<ByteArray>,
)

/** Splits an interleaved Profile-7 access unit without ever claiming that EL was composed. */
fun splitDolbyVisionLayers(
    data: ByteArray,
    packing: YSamplePacking,
    presentationTimeUs: Long,
): YDolbyVisionLayerAccessUnit {
    val units = YBitstream.scan(data, YNalCodec.H265, packing)
    require(units.isNotEmpty()) { "Dolby Vision HEVC access unit contains no NAL units" }
    fun encoded(types: Set<Int>): List<ByteArray> =
        units.filter { it.type in types }.map { span ->
            ANNEX_B_START + data.copyOfRange(span.offset, span.offset + span.length)
        }
    val base = encoded((0..63).filterNot { it == DOVI_RPU_NAL || it == DOVI_EL_NAL }.toSet())
    require(base.isNotEmpty()) { "Dolby Vision access unit contains no base layer" }
    return YDolbyVisionLayerAccessUnit(
        presentationTimeUs = presentationTimeUs,
        baseLayer = base.flattenBytes(),
        rpu = encoded(setOf(DOVI_RPU_NAL)),
        enhancementLayer = encoded(setOf(DOVI_EL_NAL)),
    )
}

data class YDolbyVisionSynchronizedLayers(
    val base: YCompressedSample,
    val enhancement: YCompressedSample,
    val presentationTimeErrorUs: Long,
)

/**
 * Bounded PTS join for dual-track Profile-7 streams and seamless-branch boundaries.
 * A discontinuity flushes unmatched data so an old EL can never be applied to a new BL frame.
 */
class YDolbyVisionLayerSynchronizer(
    private val toleranceUs: Long = 250,
    private val maximumQueuedSamples: Int = 8,
) {
    private val base = ArrayDeque<YCompressedSample>()
    private val enhancement = ArrayDeque<YCompressedSample>()

    init {
        require(toleranceUs in 0..10_000)
        require(maximumQueuedSamples in 1..32)
    }

    fun offerBase(sample: YCompressedSample): YDolbyVisionSynchronizedLayers? = offer(sample, base)

    fun offerEnhancement(sample: YCompressedSample): YDolbyVisionSynchronizedLayers? = offer(sample, enhancement)

    fun onDiscontinuity() {
        base.clear()
        enhancement.clear()
    }

    private fun offer(
        sample: YCompressedSample,
        queue: ArrayDeque<YCompressedSample>,
    ): YDolbyVisionSynchronizedLayers? {
        queue += sample
        while (queue.size > maximumQueuedSamples) queue.removeFirst()
        while (base.isNotEmpty() && enhancement.isNotEmpty()) {
            val baseSample = base.first()
            val enhancementSample = enhancement.first()
            val difference = enhancementSample.presentationTimeUs - baseSample.presentationTimeUs
            when {
                kotlin.math.abs(difference) <= toleranceUs -> {
                    base.removeFirst()
                    enhancement.removeFirst()
                    return YDolbyVisionSynchronizedLayers(baseSample, enhancementSample, kotlin.math.abs(difference))
                }
                difference < 0 -> enhancement.removeFirst()
                else -> base.removeFirst()
            }
        }
        return null
    }
}

private fun List<ByteArray>.flattenBytes(): ByteArray {
    val output = ByteArray(sumOf(ByteArray::size))
    var offset = 0
    forEach { bytes ->
        bytes.copyInto(output, offset)
        offset += bytes.size
    }
    return output
}

private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)
private const val DOVI_RPU_NAL = 62
private const val DOVI_EL_NAL = 63
