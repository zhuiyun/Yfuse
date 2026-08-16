package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackHealthTest {
    @Test
    fun sustained_frame_loss_penalizes_the_engine_after_a_real_observation_window() {
        val assessment =
            assessPlaybackHealth(
                PlaybackHealthSample(
                    startupTimeMs = 1_200L,
                    observedPlaybackMs = 60_000L,
                    rebufferEvents = 0,
                    droppedFrames = 35,
                ),
            )

        assertEquals(PlaybackHealthGrade.Critical, assessment.grade)
        assertTrue(assessment.enginePenaltyRecommended)
    }

    @Test
    fun network_rebuffering_degrades_health_without_blacklisting_a_decoder() {
        val assessment =
            assessPlaybackHealth(
                PlaybackHealthSample(
                    startupTimeMs = 2_000L,
                    observedPlaybackMs = 60_000L,
                    rebufferEvents = 4,
                    droppedFrames = 0,
                ),
            )

        assertEquals(PlaybackHealthGrade.Critical, assessment.grade)
        assertFalse(assessment.enginePenaltyRecommended)
    }

    @Test
    fun health_session_excludes_initial_prepare_buffering_and_counts_rendered_time() {
        val session =
            PlaybackHealthSession(
                startedAtEpochMs = 1_000L,
                initialPositionMs = 5_000L,
                initialBufferEvents = 1,
                initialDroppedFrames = 2,
            )

        session.observe(
            nowEpochMs = 2_000L,
            positionMs = 5_000L,
            activelyRendering = false,
            videoReady = false,
            bufferEvents = 2,
            droppedFrames = 2,
        )
        session.observe(
            nowEpochMs = 3_000L,
            positionMs = 5_100L,
            activelyRendering = true,
            videoReady = true,
            bufferEvents = 2,
            droppedFrames = 2,
        )
        val assessment =
            session.observe(
                nowEpochMs = 8_000L,
                positionMs = 10_100L,
                activelyRendering = true,
                videoReady = true,
                bufferEvents = 3,
                droppedFrames = 3,
            )

        assertEquals(2_000L, assessment.startupTimeMs)
        assertEquals(5_000L, assessment.observedPlaybackMs)
        assertEquals(1, assessment.rebufferEvents)
        assertEquals(1, assessment.droppedFrames)
    }

    @Test
    fun power_estimate_distinguishes_platform_hardware_from_gpu_tone_mapping() {
        val probe = probe()
        val efficient =
            playbackPowerAssessment(
                plan = plan(PlaybackRenderPath.PlatformDirect, DecoderMode.Hardware),
                probe = probe,
            )
        val intensive =
            playbackPowerAssessment(
                plan = plan(PlaybackRenderPath.GpuToneMapped, DecoderMode.Hardware),
                probe = probe,
            )

        assertEquals(PlaybackPowerProfile.Efficient, efficient.profile)
        assertEquals(PlaybackPowerProfile.Intensive, intensive.profile)
    }

    @Test
    fun measured_battery_power_is_kept_separate_from_the_route_estimate() {
        val measured =
            playbackPowerAssessment(
                plan = plan(PlaybackRenderPath.PlatformDirect, DecoderMode.Hardware),
                probe = probe(),
            ).withMeasuredPower(2_150)

        assertEquals(PlaybackPowerProfile.Efficient, measured.profile)
        assertEquals(2_150, measured.measuredMilliwatts)
        assertTrue("实测 2.2W" in measured.diagnosticLabel)
    }

    private fun plan(
        path: PlaybackRenderPath,
        decoderMode: DecoderMode,
    ): PlaybackPlan =
        PlaybackPlan(
            primaryEngine = PlayerEngine.Exo,
            decoderMode = decoderMode,
            renderPath = path,
            requiresServerTranscode = false,
            engineOrder = listOf(PlayerEngine.Exo),
        )

    private fun probe(): PlaybackMediaProbe =
        PlaybackMediaProbe(
            container = "MKV",
            discSource = false,
            source =
                PlaybackSourceRequirements(
                    dolbyVision = false,
                    needsDolbyDecoder = false,
                    dynamicRange = "HDR10",
                    width = 3_840,
                    height = 2_160,
                ),
            hasServerTranscode = true,
        )
}
