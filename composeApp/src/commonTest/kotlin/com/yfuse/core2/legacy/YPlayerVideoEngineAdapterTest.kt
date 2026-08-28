package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class YPlayerVideoEngineAdapterTest {
    @Test
    fun `product player binding unwraps the native Core2 player`() {
        val player = FakeYPlayer()

        assertSame(player, YPlayerVideoEngineAdapter(player).asYPlayer())
    }

    @Test
    fun `Core2 state keeps machine failure and verified output evidence in Legacy UI`() {
        val player = FakeYPlayer()
        val engine = YPlayerVideoEngineAdapter(player)
        player.mutableState.value =
            YPlayerState(
                phase = YPlaybackPhase.Failed,
                positionMs = 5_000L,
                durationMs = 10_000L,
                discNavigation =
                    PlaybackDiscNavigationState(
                        kind = PlaybackDiscKind.BluRay,
                        chapterCount = 8,
                    ),
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
                        dolbyVisionRpuApplied = true,
                        dolbyVisionEnhancementLayerDelivered = true,
                        dolbyVisionFelComposed = false,
                    ),
            )

        val state = engine.state.value
        assertEquals(PlaybackFailureKind.Decoder, state.errorKind)
        assertTrue(state.fallbacksExhausted)
        assertFalse(state.automaticFallbackBlocked)
        assertTrue(state.diagnostics.dolbyVisionOutput)
        assertTrue(state.diagnostics.dolbyVisionRpuApplied)
        assertFalse(state.diagnostics.dolbyVisionEnhancementLayerComposed)
        assertEquals("c2.vendor.hevc.decoder", state.diagnostics.decoder)
        assertEquals(8, state.discNavigation.chapterCount)
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
        assertTrue(engine.selectDiscTitle(1))
        assertTrue(engine.selectDiscChapter(3))
        assertTrue(engine.sendDiscMenuCommand(PlaybackDiscMenuCommand.ShowMenu))

        assertTrue(player.playCalled)
        assertEquals(12_000L, player.seekMs)
        assertEquals(1.5f, player.recordedSpeed)
        assertEquals(YTrackType.Audio to "audio:2", player.selectedTracks[0])
        assertEquals(YTrackType.Subtitle to "sub:3", player.selectedTracks[1])
        assertEquals(4, player.itemIndex)
        assertEquals(1, player.discTitle)
        assertEquals(3, player.discChapter)
        assertEquals(PlaybackDiscMenuCommand.ShowMenu, player.discCommand)
    }

    @Test
    fun `handover pauses and detaches the outgoing Core2 output`() {
        val player = FakeYPlayer().also { it.playCalled = true }
        val engine = YPlayerVideoEngineAdapter(player)

        engine.prepareForHandover()

        assertFalse(player.playCalled)
        assertTrue(player.videoOutputDetached)
    }

    @Test
    fun `Core2 presentation controls are acknowledged without a false MPV fallback`() {
        val engine = YPlayerVideoEngineAdapter(FakeYPlayer())

        assertTrue(engine.supportsSubtitleOffset)
        assertTrue(engine.supportsSubtitleScale)
        assertTrue(engine.supportsSubtitleBrightness)
        assertTrue(engine.supportsSubtitlePosition)
        assertTrue(engine.setSubtitleOffsetMs(2_000L))
        assertTrue(engine.setSubtitleScale(1.25f))
        assertTrue(engine.setSubtitleBrightness(0.6f))
        assertTrue(engine.setSubtitlePosition(0.88f))
        assertFalse(engine.supportsAudioDelay)
        assertFalse(engine.setAudioDelayMs(500L))
    }

    private class FakeYPlayer : YPlayer {
        val mutableState = MutableStateFlow(YPlayerState())
        override val state = mutableState
        var playCalled = false
        var seekMs = 0L
        var recordedSpeed = 1f
        val selectedTracks = mutableListOf<Pair<YTrackType, String>>()
        var itemIndex = 0
        var discTitle: Int? = null
        var discChapter: Int? = null
        var discCommand: PlaybackDiscMenuCommand? = null
        var videoOutputDetached = false

        override fun setVideoOutput(output: YVideoOutput?): Boolean {
            videoOutputDetached = output == null
            return true
        }

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
            recordedSpeed = speed
        }

        override fun selectTrack(
            type: YTrackType,
            id: String,
        ) {
            selectedTracks += type to id
        }

        override fun selectItem(index: Int) {
            itemIndex = index
        }

        override fun selectDiscTitle(index: Int): Boolean {
            discTitle = index
            return true
        }

        override fun selectDiscChapter(index: Int): Boolean {
            discChapter = index
            return true
        }

        override fun sendDiscMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
            discCommand = command
            return true
        }

        override fun retry() = Unit

        override fun release() = Unit
    }
}
