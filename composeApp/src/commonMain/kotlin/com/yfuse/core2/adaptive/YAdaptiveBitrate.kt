package com.yfuse.core2.adaptive

data class YAdaptiveSelectionConditions(
    val estimatedBandwidthBitsPerSecond: Long,
    val bufferedDurationUs: Long,
    val maximumWidth: Int? = null,
    val maximumHeight: Int? = null,
    val metered: Boolean = false,
) {
    init {
        require(estimatedBandwidthBitsPerSecond >= 0L)
        require(bufferedDurationUs >= 0L)
        require(maximumWidth == null || maximumWidth > 0)
        require(maximumHeight == null || maximumHeight > 0)
    }
}

data class YHlsVariantMediaPlaylist(
    val variant: YAdaptiveVariant,
    val playlist: YHlsPlaylist.Media,
)

data class YHlsAlignedSegmentResource(
    val variant: YAdaptiveVariant,
    val uri: String,
)

data class YHlsAlignedSegment(
    val sequence: Long,
    val durationUs: Long,
    val resources: List<YHlsAlignedSegmentResource>,
)

/**
 * Builds a switch-safe muxed HLS ladder around one already selected media playlist.
 *
 * A candidate is admitted only when codec families, timing, byte ranges, discontinuities,
 * initialization data, and encryption metadata match. This deliberately excludes representation
 * changes that would require a new decoder configuration or a different key/map tag.
 */
fun alignYHlsVariantSegments(
    variants: List<YHlsVariantMediaPlaylist>,
    selectedVariantId: String,
): List<YHlsAlignedSegment> {
    require(variants.isNotEmpty())
    require(variants.distinctBy { it.variant.id }.size == variants.size) { "HLS variant ids are not unique" }
    val selected = variants.singleOrNull { it.variant.id == selectedVariantId } ?: error("Selected HLS variant is missing")
    val selectedCodecFamilies = selected.variant.codecFamilies()
    val compatibleVariants =
        variants.filter { candidate ->
            candidate.variant.id == selectedVariantId ||
                selectedCodecFamilies.isNotEmpty() &&
                candidate.variant.codecFamilies() == selectedCodecFamilies &&
                candidate.playlist.isLive == selected.playlist.isLive
        }
    val segmentsByVariant =
        compatibleVariants.associateWith { candidate -> candidate.playlist.segments.associateBy(YAdaptiveSegment::sequence) }
    return selected.playlist.segments.map { selectedSegment ->
        YHlsAlignedSegment(
            sequence = selectedSegment.sequence,
            durationUs = selectedSegment.durationUs,
            resources =
                compatibleVariants.mapNotNull { candidate ->
                    val segment = segmentsByVariant.getValue(candidate)[selectedSegment.sequence]
                    segment
                        ?.takeIf { it.switchCompatibleWith(selectedSegment) }
                        ?.let { YHlsAlignedSegmentResource(candidate.variant, it.uri) }
                },
        ).also { aligned ->
            require(aligned.resources.any { it.variant.id == selectedVariantId }) {
                "Selected HLS segment disappeared from its own ladder"
            }
        }
    }
}

/** Throughput EWMA that ignores zero-length and implausibly short samples. */
class YAdaptiveBandwidthEstimator(
    private val previousWeightPermille: Int = 700,
) {
    init {
        require(previousWeightPermille in 0..999)
    }

    var estimateBitsPerSecond: Long = 0L
        private set

    fun addSample(
        bytes: Long,
        durationMs: Long,
    ): Long {
        if (bytes <= 0L || durationMs < MIN_SAMPLE_DURATION_MS) return estimateBitsPerSecond
        val sample =
            bytes
                .coerceAtMost(Long.MAX_VALUE / (BITS_PER_BYTE * MILLIS_PER_SECOND))
                .times(BITS_PER_BYTE)
                .times(MILLIS_PER_SECOND)
                .div(durationMs)
                .coerceAtLeast(1L)
        estimateBitsPerSecond =
            if (estimateBitsPerSecond == 0L) {
                sample
            } else {
                weightedAverage(estimateBitsPerSecond, sample, previousWeightPermille)
            }
        return estimateBitsPerSecond
    }
}

/**
 * Deterministic ABR selector. Downshifts immediately under pressure; upshifts require both spare
 * bandwidth and a healthy buffer to prevent oscillation between adjacent renditions.
 */
object YAdaptiveVariantSelector {
    fun select(
        variants: List<YAdaptiveVariant>,
        conditions: YAdaptiveSelectionConditions,
        currentVariantId: String? = null,
    ): YAdaptiveVariant {
        require(variants.isNotEmpty())
        val eligible =
            variants
                .filter { variant ->
                    (
                        conditions.maximumWidth == null ||
                            variant.width == null ||
                            variant.width <= conditions.maximumWidth
                    ) &&
                        (
                            conditions.maximumHeight == null ||
                                variant.height == null ||
                                variant.height <= conditions.maximumHeight
                        )
                }.ifEmpty { variants }
                .sortedBy(YAdaptiveVariant::selectionBandwidthBitsPerSecond)
        val budget =
            conditions.estimatedBandwidthBitsPerSecond
                .times(if (conditions.metered) METERED_BUDGET_PERCENT else NORMAL_BUDGET_PERCENT)
                .div(100L)
        val ideal =
            eligible.lastOrNull { it.selectionBandwidthBitsPerSecond <= budget }
                ?: eligible.first()
        val current = eligible.firstOrNull { it.id == currentVariantId } ?: return ideal
        if (ideal.id == current.id) return current
        if (conditions.bufferedDurationUs < LOW_BUFFER_US) return eligible.first()
        if (
            ideal.selectionBandwidthBitsPerSecond < current.selectionBandwidthBitsPerSecond ||
            current.selectionBandwidthBitsPerSecond > budget
        ) {
            return ideal
        }
        val upgradeBudget = ideal.selectionBandwidthBitsPerSecond * UPGRADE_HEADROOM_PERCENT / 100L
        return if (
            conditions.bufferedDurationUs >= UPGRADE_BUFFER_US &&
            budget >= upgradeBudget
        ) {
            ideal
        } else {
            current
        }
    }
}

private fun YAdaptiveVariant.codecFamilies(): List<String> =
    codecs
        .map { codec -> codec.substringBefore('.').trim().lowercase() }
        .filter(String::isNotEmpty)

private fun YAdaptiveSegment.switchCompatibleWith(reference: YAdaptiveSegment): Boolean =
    kotlin.math.abs(durationUs - reference.durationUs) <=
        maxOf(MAX_HLS_SEGMENT_DRIFT_US, reference.durationUs / MAX_HLS_SEGMENT_DRIFT_DIVISOR) &&
        byteRange == reference.byteRange &&
        discontinuity == reference.discontinuity &&
        initialization == reference.initialization &&
        encryption == reference.encryption

private fun weightedAverage(
    previous: Long,
    sample: Long,
    previousWeightPermille: Int,
): Long {
    val sampleWeight = 1_000 - previousWeightPermille
    return previous / 1_000L * previousWeightPermille +
        sample / 1_000L * sampleWeight +
        (previous % 1_000L * previousWeightPermille + sample % 1_000L * sampleWeight) / 1_000L
}

private const val MIN_SAMPLE_DURATION_MS = 20L
private const val BITS_PER_BYTE = 8L
private const val MILLIS_PER_SECOND = 1_000L
private const val NORMAL_BUDGET_PERCENT = 75L
private const val METERED_BUDGET_PERCENT = 55L
private const val UPGRADE_HEADROOM_PERCENT = 125L
private const val LOW_BUFFER_US = 2_000_000L
private const val UPGRADE_BUFFER_US = 10_000_000L
private const val MAX_HLS_SEGMENT_DRIFT_US = 50_000L
private const val MAX_HLS_SEGMENT_DRIFT_DIVISOR = 100L
