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
        assertEquals(DiscNavigationBackendLifecycle.Ready, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.None, backend.status.menuRuntime)
        assertFalse(backend.status.interactiveMenuReady)
        assertTrue(backend.selectTitle(1))
        assertEquals(1, engine.lastTitle)
        assertTrue(backend.selectChapter(4))
        assertEquals(4, engine.lastChapter)
        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.ShowMenu))
    }

    @Test
    fun outgoing_engine_cannot_clear_a_newer_active_navigation_binding() {
        val firstOwner = Any()
        val secondOwner = Any()
        val first = FakeDiscEngine()
        val second = FakeDiscEngine()

        ActiveDiscNavigation.bind(firstOwner, VideoEngineDiscNavigationBackend(first))
        ActiveDiscNavigation.bind(secondOwner, VideoEngineDiscNavigationBackend(second))
        ActiveDiscNavigation.unbind(firstOwner)

        assertTrue(ActiveDiscNavigation.isBound)
        assertTrue(ActiveDiscNavigation.selectTitle(1))
        assertEquals(null, first.lastTitle)
        assertEquals(1, second.lastTitle)

        ActiveDiscNavigation.unbind(secondOwner)
        assertFalse(ActiveDiscNavigation.isBound)
        assertFalse(ActiveDiscNavigation.selectChapter(1))
    }

    @Test
    fun menu_commands_are_routed_only_when_a_real_runtime_reports_an_active_menu() {
        val owner = Any()
        val backend = FakeInteractiveMenuBackend()
        ActiveDiscNavigation.bind(owner, backend)

        assertTrue(ActiveDiscNavigation.status.interactiveMenuReady)
        assertFalse(ActiveDiscNavigation.menuActive)
        assertFalse(ActiveDiscNavigation.routeActiveMenuCommand(PlaybackDiscMenuCommand.Down))
        assertTrue(backend.commands.isEmpty())

        backend.menuActive = true
        assertTrue(ActiveDiscNavigation.menuActive)
        assertTrue(ActiveDiscNavigation.routeActiveMenuCommand(PlaybackDiscMenuCommand.Down))
        assertEquals(listOf(PlaybackDiscMenuCommand.Down), backend.commands)

        ActiveDiscNavigation.unbind(owner)
        assertTrue(backend.closed)
        assertFalse(ActiveDiscNavigation.menuActive)
    }
}

private class FakeInteractiveMenuBackend : DiscNavigationBackend {
    var menuActive: Boolean = false
    var closed: Boolean = false
    val commands = mutableListOf<PlaybackDiscMenuCommand>()

    override val navigation: PlaybackDiscNavigationState
        get() =
            PlaybackDiscNavigationState(
                kind = PlaybackDiscKind.BluRay,
                titleCount = 1,
                menuSupported = true,
                menuActive = menuActive,
            )

    override val status: DiscNavigationBackendStatus =
        DiscNavigationBackendStatus(
            lifecycle = DiscNavigationBackendLifecycle.Ready,
            menuRuntime = DiscMenuRuntime.Hdmv,
        )

    override fun selectTitle(index: Int): Boolean = index == 0

    override fun selectChapter(index: Int): Boolean = false

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        commands += command
        return true
    }

    override fun close() {
        closed = true
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
