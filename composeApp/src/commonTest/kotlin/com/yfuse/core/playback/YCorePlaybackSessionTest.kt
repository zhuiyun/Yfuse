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

    private fun session(memory: PlaybackFailureMemory): YCorePlaybackSession =
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
                "UnknownAudio|UnknownChannels|None|PlainSubtitles|Clear|Original"
    }
}
