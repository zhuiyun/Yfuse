package com.yfuse.feature.player

import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlaybackOutputCapabilitiesTest {
    @Test
    fun persisted_modes_fail_closed() {
        assertEquals(FrameRateMatchMode.Always, FrameRateMatchMode.fromStorage("always"))
        assertEquals(FrameRateMatchMode.Disabled, FrameRateMatchMode.fromStorage("future-value"))
        assertEquals(AudioPassthroughMode.Compatible, AudioPassthroughMode.fromStorage("compatible"))
        assertEquals(AudioPassthroughMode.Disabled, AudioPassthroughMode.fromStorage(null))
    }

    @Test
    fun android_11_exposes_only_the_seamless_frame_rate_strategy() {
        val capability =
            assertIs<PlaybackFeatureCapability.Available<FrameRateMatchMode>>(
                PlaybackOutputCapabilities
                    .forEngine(PlayerEngine.Exo, androidApiLevel = 30)
                    .frameRateMatching,
            )

        assertEquals(setOf(FrameRateMatchMode.SeamlessOnly), capability.modes)
    }

    @Test
    fun android_12_exposes_explicit_non_seamless_matching_for_exo_and_mpv() {
        listOf(PlayerEngine.Exo, PlayerEngine.Mpv).forEach { engine ->
            val capability =
                assertIs<PlaybackFeatureCapability.Available<FrameRateMatchMode>>(
                    PlaybackOutputCapabilities
                        .forEngine(engine, androidApiLevel = 31)
                        .frameRateMatching,
                )
            assertEquals(
                setOf(FrameRateMatchMode.SeamlessOnly, FrameRateMatchMode.Always),
                capability.modes,
            )
        }
    }

    @Test
    fun mdk_never_claims_an_unverified_backend_feature() {
        val capabilities = PlaybackOutputCapabilities.forEngine(PlayerEngine.Mdk, androidApiLevel = 36)

        assertEquals(
            PlaybackOutputUnsupportedReason.BackendApiNotVerified,
            assertIs<PlaybackFeatureCapability.Unsupported>(capabilities.frameRateMatching).reason,
        )
        assertEquals(
            PlaybackOutputUnsupportedReason.BackendApiNotVerified,
            assertIs<PlaybackFeatureCapability.Unsupported>(capabilities.audioPassthrough).reason,
        )
    }

    @Test
    fun surface_plan_rejects_old_platforms_bad_rates_and_android_11_always_mode() {
        assertIs<SurfaceFrameRatePlan.Unsupported>(
            surfaceFrameRatePlan(FrameRateMatchMode.SeamlessOnly, 24f, androidApiLevel = 29),
        )
        assertIs<SurfaceFrameRatePlan.Invalid>(
            surfaceFrameRatePlan(FrameRateMatchMode.SeamlessOnly, Float.NaN, androidApiLevel = 36),
        )
        assertIs<SurfaceFrameRatePlan.Unsupported>(
            surfaceFrameRatePlan(FrameRateMatchMode.Always, 23.976f, androidApiLevel = 30),
        )
    }

    @Test
    fun surface_plan_keeps_requested_rate_and_strategy_truth() {
        val seamless =
            assertIs<SurfaceFrameRatePlan.Apply>(
                surfaceFrameRatePlan(FrameRateMatchMode.SeamlessOnly, 23.976f, androidApiLevel = 31),
            )
        val always =
            assertIs<SurfaceFrameRatePlan.Apply>(
                surfaceFrameRatePlan(FrameRateMatchMode.Always, 25f, androidApiLevel = 36),
            )

        assertEquals(23.976f, seamless.frameRate)
        assertEquals(false, seamless.allowNonSeamlessSwitch)
        assertEquals(true, always.allowNonSeamlessSwitch)
        assertEquals(true, always.useExplicitStrategyApi)
    }

    @Test
    fun mpv_spdif_is_configured_only_for_compatible_mode() {
        assertNull(mpvAudioSpdifOption(AudioPassthroughMode.Disabled))
        assertEquals(
            "ac3,eac3,dts,dts-hd,truehd",
            mpvAudioSpdifOption(AudioPassthroughMode.Compatible),
        )
    }

    @Test
    fun mpv_does_not_report_active_until_output_proves_spdif() {
        assertIs<PlaybackOutputStatus.Configured>(
            mpvAudioPassthroughStatus(AudioPassthroughMode.Compatible, null, null),
        )
        assertIs<PlaybackOutputStatus.Inactive>(
            mpvAudioPassthroughStatus(AudioPassthroughMode.Compatible, "float", "aac"),
        )
        assertIs<PlaybackOutputStatus.Active>(
            mpvAudioPassthroughStatus(AudioPassthroughMode.Compatible, "spdif-eac3", "spdif_eac3"),
        )
    }
}
