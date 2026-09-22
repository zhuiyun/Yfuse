package com.yfuse.core2.legacy

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import com.yfuse.core2.api.YPlaybackRoute
import com.yfuse.core2.api.YTrackType
import com.yfuse.feature.player.EngineTrack
import com.yfuse.feature.player.PlaybackDiagnostics
import com.yfuse.feature.player.PlaybackState
import com.yfuse.feature.player.VideoEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LegacyYPlayerAdapterTest {
    @Test
    fun `legacy state is exposed through the unified player contract`() {
        val engine = FakeVideoEngine()
        val player = LegacyYPlayerAdapter(engine)
        engine.mutableState.value =
            PlaybackState(
                playing = true,
                buffering = false,
                positionMs = 12_345L,
                durationMs = 90_000L,
                diagnostics = PlaybackDiagnostics(bufferEvents = 4),
                discNavigation =
                    PlaybackDiscNavigationState(
                        kind = PlaybackDiscKind.BluRay,
                        titleCount = 2,
                    ),
                audioTracks =
                    listOf(
                        EngineTrack(
                            id = "audio-1",
                            label = "English",
                            language = "en",
                            selected = true,
                            codec = "eac3",
                        ),
                    ),
            )

        assertTrue(player.state.value.playing)
        assertEquals(4, player.state.value.diagnostics.bufferEvents)
        assertEquals(12_345L, player.state.value.positionMs)
        assertEquals(YPlaybackRoute.Legacy, player.state.value.diagnostics.route)
        assertEquals(
            "audio-1",
            player.state.value.audioTracks
                .single()
                .id,
        )
        assertEquals(PlaybackDiscKind.BluRay, player.state.value.discNavigation.kind)
        assertSame(engine.mutableState, player.asPlaybackStateFlow())
    }

    @Test
    fun `unified controls forward to the legacy engine`() {
        val engine = FakeVideoEngine()
        val player = engine.asYPlayer()

        player.play()
        player.seekTo(42_000L)
        player.setSpeed(1.25f)
        player.selectTrack(YTrackType.Audio, "a2")
        player.selectTrack(YTrackType.Subtitle, "s2")
        player.selectItem(3)
        assertTrue(player.selectDiscTitle(1))
        assertTrue(player.selectDiscChapter(2))
        assertTrue(player.sendDiscMenuCommand(PlaybackDiscMenuCommand.ShowMenu))

        assertTrue(engine.playCalled)
        assertEquals(42_000L, engine.seekPositionMs)
        assertEquals(1.25f, engine.recordedSpeed)
        assertEquals("a2", engine.audioTrack)
        assertEquals("s2", engine.subtitleTrack)
        assertEquals(3, engine.itemIndex)
        assertEquals(1, engine.discTitle)
        assertEquals(2, engine.discChapter)
        assertEquals(PlaybackDiscMenuCommand.ShowMenu, engine.discCommand)
    }

    private class FakeVideoEngine : VideoEngine {
        val mutableState = MutableStateFlow(PlaybackState(buffering = false))
        override val state = mutableState
        override val playbackRequested: Boolean get() = playCalled

        var playCalled = false
        var seekPositionMs = 0L
        var recordedSpeed = 1f
        var audioTrack: String? = null
        var subtitleTrack: String? = null
        var itemIndex = 0
        var discTitle: Int? = null
        var discChapter: Int? = null
        var discCommand: PlaybackDiscMenuCommand? = null

        override fun play() {
            playCalled = true
        }

        override fun pause() {
            playCalled = false
        }

        override fun seekTo(positionMs: Long) {
            seekPositionMs = positionMs
        }

        override fun setSpeed(speed: Float) {
            recordedSpeed = speed
        }

        override fun selectAudioTrack(id: String) {
            audioTrack = id
        }

        override fun selectSubtitleTrack(id: String) {
            subtitleTrack = id
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

        override fun currentPositionMs(): Long = seekPositionMs

        override fun retry() = Unit

        override fun release() = Unit
    }
}
