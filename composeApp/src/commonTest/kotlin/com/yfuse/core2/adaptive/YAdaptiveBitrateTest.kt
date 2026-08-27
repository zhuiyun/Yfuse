package com.yfuse.core2.adaptive

import kotlin.test.Test
import kotlin.test.assertEquals

class YAdaptiveBitrateTest {
    private val variants =
        listOf(
            variant("low", 500_000L, 640, 360),
            variant("mid", 1_500_000L, 1280, 720),
            variant("high", 4_000_000L, 3840, 2160),
        )

    @Test
    fun estimator_smooths_samples_without_accepting_zero_duration() {
        val estimator = YAdaptiveBandwidthEstimator(previousWeightPermille = 500)

        assertEquals(0L, estimator.addSample(bytes = 1_000L, durationMs = 0L))
        assertEquals(8_000_000L, estimator.addSample(bytes = 1_000_000L, durationMs = 1_000L))
        assertEquals(6_000_000L, estimator.addSample(bytes = 500_000L, durationMs = 1_000L))
    }

    @Test
    fun selector_downshifts_immediately_but_requires_buffer_for_upgrade() {
        val lowBuffer =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond = 8_000_000L,
                bufferedDurationUs = 1_000_000L,
            )
        assertEquals("low", YAdaptiveVariantSelector.select(variants, lowBuffer, "mid").id)

        val recovering = lowBuffer.copy(bufferedDurationUs = 5_000_000L)
        assertEquals("mid", YAdaptiveVariantSelector.select(variants, recovering, "mid").id)

        val healthy = lowBuffer.copy(bufferedDurationUs = 12_000_000L)
        assertEquals("high", YAdaptiveVariantSelector.select(variants, healthy, "mid").id)
    }

    @Test
    fun device_resolution_and_metered_budget_bound_the_selection() {
        val conditions =
            YAdaptiveSelectionConditions(
                estimatedBandwidthBitsPerSecond = 8_000_000L,
                bufferedDurationUs = 12_000_000L,
                maximumWidth = 1920,
                maximumHeight = 1080,
                metered = true,
            )

        assertEquals("mid", YAdaptiveVariantSelector.select(variants, conditions).id)
    }

    private fun variant(
        id: String,
        bandwidth: Long,
        width: Int,
        height: Int,
    ) = YAdaptiveVariant(
        id = id,
        uri = "https://media.example.test/$id.m3u8",
        bandwidthBitsPerSecond = bandwidth,
        width = width,
        height = height,
    )
}
