package com.yfuse.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackAdaptiveNetworkTest {
    @Test
    fun repeated_rebuffers_request_one_quality_downgrade_when_bandwidth_is_constrained() {
        val controller = PlaybackAdaptiveNetworkController()

        assertFalse(
            controller
                .observe(
                    sample(
                        bufferEvents = 0,
                        networkBitsPerSecond = 5_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                    ),
                ).downgradeRecommended,
        )
        assertFalse(
            controller
                .observe(
                    sample(
                        bufferEvents = 1,
                        networkBitsPerSecond = 5_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                    ),
                ).downgradeRecommended,
        )
        assertTrue(
            controller
                .observe(
                    sample(
                        bufferEvents = 2,
                        networkBitsPerSecond = 5_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                    ),
                ).downgradeRecommended,
        )
    }

    @Test
    fun high_throughput_rebuffers_do_not_request_quality_downgrade() {
        val controller = PlaybackAdaptiveNetworkController()

        repeat(6) { index ->
            assertFalse(
                controller
                    .observe(
                        sample(
                            nowEpochMs = 120_000L + index * 2_000L,
                            bufferEvents = index,
                            networkBitsPerSecond = 60_000_000L,
                            mediaBitsPerSecond = 14_000_000L,
                            bufferedDurationMs = 1_000L,
                        ),
                    ).downgradeRecommended,
            )
        }
    }

    @Test
    fun sustained_low_throughput_and_short_buffer_request_downgrade() {
        val controller = PlaybackAdaptiveNetworkController()

        repeat(2) { index ->
            assertFalse(
                controller
                    .observe(
                        sample(
                            nowEpochMs = 120_000L + index * 2_000L,
                            networkBitsPerSecond = 5_000_000L,
                            mediaBitsPerSecond = 8_000_000L,
                            bufferedDurationMs = 2_000L,
                        ),
                    ).downgradeRecommended,
            )
        }
        assertTrue(
            controller
                .observe(
                    sample(
                        nowEpochMs = 124_000L,
                        networkBitsPerSecond = 5_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                        bufferedDurationMs = 2_000L,
                    ),
                ).downgradeRecommended,
        )
    }

    @Test
    fun unknown_bandwidth_never_looks_like_network_pressure() {
        val controller = PlaybackAdaptiveNetworkController()

        repeat(10) {
            assertFalse(
                controller
                    .observe(
                        sample(
                            networkBitsPerSecond = 0L,
                            mediaBitsPerSecond = 20_000_000L,
                            bufferedDurationMs = 0L,
                        ),
                    ).downgradeRecommended,
            )
        }
    }

    @Test
    fun sustained_headroom_and_deep_buffer_request_one_quality_recovery_step() {
        val controller =
            PlaybackAdaptiveNetworkController(
                recoverySampleThreshold = 3,
                upgradeRecommendationCooldownMs = 60_000L,
            )

        repeat(2) { index ->
            val decision =
                controller.observe(
                    sample(
                        nowEpochMs = 120_000L + index * 2_000L,
                        networkBitsPerSecond = 20_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                        bufferedDurationMs = 30_000L,
                    ),
                )
            assertFalse(decision.upgradeRecommended)
        }
        assertTrue(
            controller
                .observe(
                    sample(
                        nowEpochMs = 124_000L,
                        networkBitsPerSecond = 20_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                        bufferedDurationMs = 30_000L,
                    ),
                ).upgradeRecommended,
        )
    }

    @Test
    fun recovery_cooldown_prevents_immediate_quality_oscillation() {
        val controller =
            PlaybackAdaptiveNetworkController(
                rebufferThreshold = 1,
                recoverySampleThreshold = 1,
                recommendationCooldownMs = 0L,
                upgradeRecommendationCooldownMs = 60_000L,
            )

        controller.observe(
            sample(
                nowEpochMs = 120_000L,
                bufferEvents = 0,
                networkBitsPerSecond = 5_000_000L,
                mediaBitsPerSecond = 8_000_000L,
            ),
        )
        assertTrue(
            controller
                .observe(
                    sample(
                        nowEpochMs = 122_000L,
                        bufferEvents = 1,
                        networkBitsPerSecond = 5_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                    ),
                ).downgradeRecommended,
        )
        assertFalse(
            controller
                .observe(
                    sample(
                        nowEpochMs = 124_000L,
                        bufferEvents = 1,
                        networkBitsPerSecond = 20_000_000L,
                        mediaBitsPerSecond = 8_000_000L,
                        bufferedDurationMs = 30_000L,
                    ),
                ).upgradeRecommended,
        )
    }

    private fun sample(
        nowEpochMs: Long = 120_000L,
        bufferEvents: Int = 0,
        networkBitsPerSecond: Long = 0L,
        mediaBitsPerSecond: Long = 0L,
        bufferedDurationMs: Long = 10_000L,
    ) = PlaybackNetworkSample(
        nowEpochMs = nowEpochMs,
        playbackPositionMs = 30_000L,
        bufferEvents = bufferEvents,
        bufferedDurationMs = bufferedDurationMs,
        networkBitsPerSecond = networkBitsPerSecond,
        mediaBitsPerSecond = mediaBitsPerSecond,
        buffering = false,
    )
}
