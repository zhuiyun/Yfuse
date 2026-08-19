package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackFailureKind
import com.yfuse.core2.api.YPlaybackFailureCategory
import com.yfuse.core2.api.YPlaybackPhase
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YPlayer
import com.yfuse.core2.api.YPlayerDiagnostics
import com.yfuse.core2.api.YPlayerState
import com.yfuse.core2.api.YTrackType
import com.yfuse.core2.api.YVideoOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YPlayerVideoEngineAdapterTest {
    @Test
    fun `Core2 state keeps machine failure and verified output evidence in Legacy UI`() {
        val player = FakeYPlayer()
        val engine = YPlayerVideoEngineAdapter(player)
        player.mutableState.value =
            YPlayerState(
                phase = YPlaybackPhase.Failed,
                positionMs = 5_000L,
                durationMs = 10_000L,
                error = "failed",
                errorCategory = YPlaybackFailureCategory.Decoder,
                diagnostics =
                    YPlayerDiagnostics(
                        route = YPlaybackRoute.NativeDirect,
                        demuxer = "MediaExtractor",
                        decoder = "c2.vendor.hevc.decoder",
                        renderer = "Surface",
                        dynamicRange = "Dolby Vision",
                        videoOutput = "Surface 直出",
                        videoOutputVerified = true,
                        dolbyVisionOutput = true,
                    ),
            )

        val state = engine.state.value
        assertEquals(PlaybackFailureKind.Decoder, state.errorKind)
        assertTrue(state.fallbacksExhausted)
        assertFalse(state.automaticFallbackBlocked)
        assertTrue(state.diagnostics.dolbyVisionOutput)
        assertEquals("c2.vendor.hevc.decoder", state.diagnostics.decoder)
    }

    @Test
    fun `authorization failure blocks automatic retry chain without becoming decoder penalty`() {
        val player = FakeYPlayer()
        val engine = YPlayerVideoEngineAdapter(player)
        player.mutableState.value =
            YPlayerState(
                phase = YPlaybackPhase.Failed,
                error = "denied",
                errorCategory = YPlaybackFailureCategory.Authorization,
            )

        assertEquals(PlaybackFailureKind.Authorization, engine.state.value.errorKind)
        assertTrue(engine.state.value.automaticFallbackBlocked)
    }

    @Test
    fun `Legacy UI controls are forwarded into YPlayer`() {
        val player = FakeYPlayer()
        val engine = YPlayerVideoEngineAdapter(player)

        engine.play()
        engine.seekTo(12_000L)
        engine.setSpeed(1.5f)
        engine.selectAudioTrack("audio:2")
        engine.selectSubtitleTrack("sub:3")
        engine.selectItem(4)

        assertTrue(player.playCalled)
        assertEquals(12_000L, player.seekMs)
        assertEquals(1.5f, player.speed)
        assertEquals(YTrackType.Audio to "audio:2", player.selectedTracks[0])
        assertEquals(YTrackType.Subtitle to "sub:3", player.selectedTracks[1])
        assertEquals(4, player.itemIndex)
    }

    private class FakeYPlayer : YPlayer {
        val mutableState = MutableStateFlow(YPlayerState())
        override val state = mutableState
        var playCalled = false
        var seekMs = 0L
        var speed = 1f
        val selectedTracks = mutableListOf<Pair<YTrackType, String>>()
        var itemIndex = 0

        override fun setVideoOutput(output: YVideoOutput?): Boolean = true

        override fun play() {
            playCalled = true
        }

        override fun pause() {
            playCalled = false
        }

        override fun seekTo(positionMs: Long) {
            seekMs = positionMs
        }

        override fun setSpeed(speed: Float) {
            this.speed = speed
        }

        override fun selectTrack(type: YTrackType, id: String) {
            selectedTracks += type to id
        }

        override fun selectItem(index: Int) {
            itemIndex = index
        }

        override fun retry() = Unit

        override fun release() = Unit
    }
}
