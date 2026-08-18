package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HdmvDiscNavigationBackendTest {
    @Test
    fun healthy_hdmv_session_reports_interactive_runtime_and_routes_commands() {
        val session = FakeHdmvSession()
        val backend = HdmvDiscNavigationBackend(session)

        assertEquals(DiscNavigationBackendLifecycle.Ready, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.Hdmv, backend.status.menuRuntime)
        assertTrue(backend.status.interactiveMenuReady)
        assertTrue(backend.navigation.menuActive)
        assertTrue(backend.selectTitle(1))
        assertTrue(backend.selectChapter(3))
        assertTrue(backend.selectAngle(1))
        assertTrue(backend.sendMenuCommand(PlaybackDiscMenuCommand.Select))
        assertEquals(1, session.lastTitle)
        assertEquals(3, session.lastChapter)
        assertEquals(1, session.lastAngle)
        assertEquals(PlaybackDiscMenuCommand.Select, session.lastCommand)
    }

    @Test
    fun native_failure_disables_only_the_optional_menu_backend() {
        val session = FakeHdmvSession(failOnCommand = true)
        val backend = HdmvDiscNavigationBackend(session)

        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.Down))
        assertEquals(DiscNavigationBackendLifecycle.Failed, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.None, backend.status.menuRuntime)
        assertFalse(backend.status.interactiveMenuReady)
        assertFalse(backend.navigation.menuActive)

        // The failed provider stays inert instead of repeatedly entering native code.
        assertFalse(backend.selectTitle(0))
        assertFalse(backend.selectAngle(0))
        assertEquals(null, session.lastTitle)
    }

    @Test
    fun close_is_idempotent_and_removes_interactive_menu_state() {
        val session = FakeHdmvSession()
        val backend = HdmvDiscNavigationBackend(session)

        backend.close()
        backend.close()

        assertEquals(1, session.closeCount)
        assertEquals(DiscNavigationBackendLifecycle.Unavailable, backend.status.lifecycle)
        assertFalse(backend.navigation.menuActive)
        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.Back))
    }
}

private class FakeHdmvSession(
    private val failOnCommand: Boolean = false,
) : HdmvDiscSession {
    var lastTitle: Int? = null
    var lastChapter: Int? = null
    var lastAngle: Int? = null
    var lastCommand: PlaybackDiscMenuCommand? = null
    var closeCount: Int = 0

    override fun navigation(): PlaybackDiscNavigationState =
        PlaybackDiscNavigationState(
            kind = PlaybackDiscKind.BluRay,
            titleCount = 2,
            chapterCount = 4,
            angleCount = 2,
            menuSupported = true,
            menuActive = true,
        )

    override fun selectTitle(index: Int): Boolean {
        lastTitle = index
        return true
    }

    override fun selectChapter(index: Int): Boolean {
        lastChapter = index
        return true
    }

    override fun selectAngle(index: Int): Boolean {
        lastAngle = index
        return index in 0..1
    }

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        if (failOnCommand) error("native HDMV command failed")
        lastCommand = command
        return true
    }

    override fun close() {
        closeCount++
    }
}
