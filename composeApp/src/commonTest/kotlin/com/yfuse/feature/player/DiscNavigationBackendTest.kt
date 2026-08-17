package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscNavigationBackendTest {
    @Test
    fun engine_adapter_keeps_navigation_optional_and_separate_from_video_lifecycle() {
        val engine = FakeDiscEngine()
        val backend = VideoEngineDiscNavigationBackend(engine)

        assertEquals(PlaybackDiscKind.BluRay, backend.navigation.kind)
        assertEquals("正片", backend.navigation.selectedTitle?.label)
        assertTrue(backend.selectTitle(1))
        assertEquals(1, engine.lastTitle)
        assertTrue(backend.selectChapter(4))
        assertEquals(4, engine.lastChapter)
        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.ShowMenu))
    }
}

private class FakeDiscEngine : VideoEngine {
    private val mutableState =
        MutableStateFlow(
            PlaybackState(
                discNavigation =
                    PlaybackDiscNavigationState(
                        kind = PlaybackDiscKind.BluRay,
                        titleCount = 2,
                        titles =
                            listOf(
                                com.yfuse.core.playback.PlaybackDiscTitle(0, title = "正片"),
                                com.yfuse.core.playback.PlaybackDiscTitle(1, title = "花絮"),
                            ),
                    ),
            ),
        )
    override val state: StateFlow<PlaybackState> = mutableState

    var lastTitle: Int? = null
    var lastChapter: Int? = null

    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setSpeed(speed: Float) = Unit
    override fun selectAudioTrack(id: String) = Unit
    override fun selectSubtitleTrack(id: String) = Unit

    override fun selectDiscTitle(index: Int): Boolean {
        lastTitle = index
        return true
    }

    override fun selectDiscChapter(index: Int): Boolean {
        lastChapter = index
        return true
    }

    override fun selectItem(index: Int) = Unit
    override fun currentPositionMs(): Long = 0L
    override fun retry() = Unit
    override fun release() = Unit
}
