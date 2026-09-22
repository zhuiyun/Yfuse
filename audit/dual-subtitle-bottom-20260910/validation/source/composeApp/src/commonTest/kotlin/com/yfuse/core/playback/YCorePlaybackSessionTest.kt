package com.yfuse.core.playback

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YCorePlaybackSessionTest {
    @Test
    fun session_records_sustained_renderer_loss_once() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        val session = session(memory)

        session.observe(observation(now = 1_000L, droppedFrames = 0))
        (6_000L..26_000L step 5_000L).forEach { now ->
            session.observe(observation(now = now, droppedFrames = 0))
        }
        val first = session.observe(observation(now = 31_000L, droppedFrames = 40))
        val second = session.observe(observation(now = 32_000L, droppedFrames = 80))

        assertTrue(first.enginePenaltyRecorded)
        assertFalse(second.enginePenaltyRecorded)
        assertEquals(1, memory.failureCount(SIGNATURE, PlayerEngine.Exo))
    }

    @Test
    fun healthy_session_clears_a_previous_engine_quirk() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        memory.record(SIGNATURE, PlayerEngine.Exo, PlaybackFailureKind.Renderer)
        val session = session(memory)

        session.observe(observation(now = 1_000L, droppedFrames = 0))
        (6_000L..26_000L step 5_000L).forEach { now ->
            session.observe(observation(now = now, droppedFrames = 0))
        }
        val result = session.observe(observation(now = 31_000L, droppedFrames = 0))

        assertTrue(result.engineCapabilityConfirmed)
        assertEquals(0, memory.failureCount(SIGNATURE, PlayerEngine.Exo))
    }

    @Test
    fun remote_startup_without_a_first_frame_is_reported_once_after_transport_budget() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        val session = session(memory)
        val waiting =
            observation(now = 60_000L, droppedFrames = 0).copy(
                positionMs = 0L,
                videoReady = false,
                videoExpected = true,
            )

        val first = session.observe(waiting)
        val second = session.observe(waiting.copy(nowEpochMs = 61_000L))

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, first.runtimeFault?.kind)
        assertEquals(null, second.runtimeFault)
        assertEquals(1, memory.failureCount(SIGNATURE, PlayerEngine.Exo))
    }

    @Test
    fun native_runtime_fault_does_not_penalize_the_compatibility_engine_identity() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        val session = session(memory, recordEngineLearning = false)
        val waiting =
            observation(now = 60_000L, droppedFrames = 0).copy(
                positionMs = 0L,
                videoReady = false,
                videoExpected = true,
            )

        val result = session.observe(waiting)

        assertEquals(PlaybackRuntimeFaultKind.StartupTimeout, result.runtimeFault?.kind)
        assertFalse(result.enginePenaltyRecorded)
        assertFalse(result.engineCapabilityConfirmed)
        assertEquals(0, memory.failureCount(SIGNATURE, PlayerEngine.Exo))
    }

    @Test
    fun intentional_buffering_does_not_trigger_a_position_stall() {
        val memory = PlaybackFailureMemory(failureThreshold = 1)
        val session = session(memory)

        session.observe(observation(now = 1_000L, droppedFrames = 0))
        val result =
            session.observe(
                observation(now = 20_000L, droppedFrames = 0).copy(
                    positionMs = 1_000L,
                    buffering = true,
                ),
            )

        assertEquals(null, result.runtimeFault)
        assertEquals(0, memory.failureCount(SIGNATURE, PlayerEngine.Exo))
    }

    @Test
    fun huge_mov_gets_a_long_but_finite_startup_budget() {
        val probe =
            probe().copy(
                container = "mov",
                sourceSizeBytes = 195_738_044_172L,
            )

        assertEquals(180_000L, playbackStartupTimeoutMs(probe))
    }

    @Test
    fun remote_direct_media_gets_time_for_transport_recovery() {
        assertEquals(60_000L, playbackStartupTimeoutMs(probe()))
    }

    @Test
    fun large_remote_source_gets_time_for_multiple_random_access_reads() {
        val probe = probe().copy(sourceSizeBytes = 5_071_883_233L)

        assertEquals(120_000L, playbackStartupTimeoutMs(probe))
    }

    @Test
    fun local_media_keeps_the_short_startup_budget() {
        assertEquals(15_000L, playbackStartupTimeoutMs(probe().copy(localSource = true)))
    }

    private fun session(
        memory: PlaybackFailureMemory,
        recordEngineLearning: Boolean = true,
    ): YCorePlaybackSession =
        YCorePlaybackSession(
            engine = PlayerEngine.Exo,
            probe = probe(),
            plan =
                PlaybackPlan(
                    primaryEngine = PlayerEngine.Exo,
                    decoderMode = DecoderMode.Hardware,
                    renderPath = PlaybackRenderPath.PlatformDirect,
                    requiresServerTranscode = false,
                    engineOrder = listOf(PlayerEngine.Exo),
                ),
            failureMemory = memory,
            recordEngineLearning = recordEngineLearning,
            startedAtEpochMs = 0L,
            initialPositionMs = 0L,
            initialBufferEvents = 0,
            initialDroppedFrames = 0,
        )

    private fun observation(
        now: Long,
        droppedFrames: Int,
    ) = YCoreRuntimeObservation(
        nowEpochMs = now,
        positionMs = now,
        playbackRequested = true,
        playing = true,
        buffering = false,
        videoReady = true,
        errorPresent = false,
        ended = false,
        bufferEvents = 0,
        droppedFrames = droppedFrames,
    )

    private fun probe() =
        PlaybackMediaProbe(
            container = "mkv",
            discSource = false,
            source = PlaybackSourceRequirements(false, false, null),
            hasServerTranscode = true,
        )

    private companion object {
        const val SIGNATURE =
            "MKV|UnknownCodec|UnknownWidth|UnknownHeight|UnknownFps|UnknownDepth|Sdr|" +
                "UnknownAudio|UnknownChannels|None|LinearMedia|PlainSubtitles|Clear|Original"
    }
}
