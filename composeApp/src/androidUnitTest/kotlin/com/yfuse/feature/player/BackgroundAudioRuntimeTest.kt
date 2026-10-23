package com.yfuse.feature.player

import com.yfuse.core.model.DecoderMode
import com.yfuse.core.model.PlayerEngine
import com.yfuse.core.playback.PlaybackAudioCodec
import com.yfuse.core.playback.PlaybackFailureMemory
import com.yfuse.core.playback.PlaybackMediaProbe
import com.yfuse.core.playback.PlaybackPlan
import com.yfuse.core.playback.PlaybackRenderPath
import com.yfuse.core.playback.PlaybackRuntimeEnvironment
import com.yfuse.core.playback.PlaybackRuntimeFaultKind
import com.yfuse.core.playback.PlaybackSourceRequirements
import com.yfuse.core.playback.PlaybackVideoCodec
import com.yfuse.core.playback.YCorePlaybackSession
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.legacy.LegacyYPlayerAdapter
import com.yfuse.core2.legacy.YPlayerVideoEngineAdapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises lifecycle intent through the real adapters, observation mapping and runtime session. */
class BackgroundAudioRuntimeTest {
    @Test
    fun background_audio_does_not_penalize_video_and_foreground_gets_a_fresh_output_grace() {
        val fixture = Fixture()
        assertNull(fixture.observe(1_000L).runtimeFault)
        // A brief foreground dropout must not consume the grace after a minute of listening.
        assertNull(fixture.observe(4_000L, videoReady = false).runtimeFault)
        fixture.background()
        (6_000L..60_000L step 2_000L).forEach { now ->
            assertNull(fixture.observe(now, videoReady = false).runtimeFault)
        }
        assertEquals(0, fixture.failureCount())

        fixture.foreground()
        assertNull(fixture.observe(62_000L, videoReady = false).runtimeFault)
        assertNull(fixture.observe(65_999L, videoReady = false).runtimeFault)
        assertEquals(
            PlaybackRuntimeFaultKind.VideoOutputMissing,
            fixture.observe(66_000L, videoReady = false).runtimeFault?.kind,
        )
        assertEquals(1, fixture.failureCount())
    }

    @Test
    fun audio_can_start_in_the_background_without_ever_producing_a_video_frame() {
        val fixture = Fixture()
        fixture.background()
        (1_000L..71_000L step 2_000L).forEach { now ->
            assertNull(fixture.observe(now, videoReady = false).runtimeFault)
        }
        fixture.foreground()
        assertNull(fixture.observe(73_000L, videoReady = false).runtimeFault)
        assertNull(fixture.observe(75_000L).runtimeFault)
        assertNull(fixture.observe(79_000L).runtimeFault)
        assertEquals(0, fixture.failureCount())
    }

    @Test
    fun missing_audio_is_still_a_fault_while_video_is_intentionally_suspended() {
        val fixture = Fixture()
        fixture.observe(1_000L)
        fixture.background()
        assertNull(fixture.observe(6_000L, videoReady = false, audioReady = false).runtimeFault)
        assertEquals(
            PlaybackRuntimeFaultKind.AudioOutputMissing,
            fixture.observe(10_000L, videoReady = false, audioReady = false).runtimeFault?.kind,
        )
    }

    @Test
    fun source_starvation_is_still_recovered_during_background_audio() {
        val fixture = Fixture()
        fixture.observe(1_000L)
        fixture.background()
        assertNull(fixture.observe(6_000L, videoReady = false).runtimeFault)
        assertNull(fixture.observe(8_000L, position = 6_000L, starving = true).runtimeFault)
        assertNull(fixture.observe(37_999L, position = 6_000L, starving = true).runtimeFault)
        assertEquals(
            PlaybackRuntimeFaultKind.RebufferTimeout,
            fixture.observe(38_000L, position = 6_000L, starving = true).runtimeFault?.kind,
        )
    }

    private class Fixture {
        private val native = TestNativePlayer()
        private val engine = YPlayerVideoEngineAdapter(native)
        private val player = LegacyYPlayerAdapter(engine)
        private val lifecycle = BackgroundAudioLifecycle()
        private val probe =
            PlaybackMediaProbe(
                container = "mkv",
                discSource = false,
                source = PlaybackSourceRequirements(false, false, null, videoCodec = PlaybackVideoCodec.H264),
                audioCodec = PlaybackAudioCodec.Aac,
                hasServerTranscode = false,
            )
        private val memory = PlaybackFailureMemory(failureThreshold = 1)
        private val session =
            YCorePlaybackSession(
                engine = PlayerEngine.Exo,
                probe = probe,
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

        fun background() {
            lifecycle.activate { player.setVideoSuspended(true) }
            assertTrue(player.videoOutputSuspended)
        }

        fun foreground() {
            assertFalse(lifecycle.foreground { player.setVideoSuspended(false) })
            assertFalse(player.videoOutputSuspended)
        }

        fun failureCount(): Int = memory.failureCount(probe.capabilitySignature, PlayerEngine.Exo)

        fun observe(
            now: Long,
            position: Long = now,
            videoReady: Boolean = true,
            audioReady: Boolean = true,
            starving: Boolean = false,
        ) = run {
            native.state.value =
                YPlayerState(
                    phase = YPlaybackPhase.Ready,
                    playbackRequested = true,
                    playing = !starving,
                    buffering = starving,
                    positionMs = position,
                    diagnostics =
                        YPlayerDiagnostics(
                            route = YPlaybackRoute.NativeDirect,
                            videoOutputVerified = videoReady && !starving,
                            audioOutputVerified = audioReady && !starving,
                            sourceStarvationCount = if (starving) 1L else 0L,
                        ),
                )
            session.observe(
                engine.state.value.runtimeObservation(
                    playbackRequested = player.playbackRequested,
                    videoSuspended = player.videoOutputSuspended,
                    probe = probe,
                    runtimeEnvironment = PlaybackRuntimeEnvironment.normal(),
                    nowEpochMs = now,
                ),
            )
        }
    }

    private class TestNativePlayer : YPlayer {
        override val state = MutableStateFlow(YPlayerState())
        private var suspended = false
        override val videoOutputSuspended: Boolean get() = suspended

        override fun setVideoSuspended(suspended: Boolean) {
            this.suspended = suspended
        }

        override fun play() = Unit

        override fun pause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun setSpeed(speed: Float) = Unit

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) = Unit

        override fun selectItem(index: Int) = Unit

        override fun retry() = Unit

        override fun release() = Unit
    }
}
