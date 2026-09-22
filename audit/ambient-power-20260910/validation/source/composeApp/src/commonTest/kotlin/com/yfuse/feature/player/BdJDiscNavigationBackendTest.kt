package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscKind
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BdJDiscNavigationBackendTest {
    @Test
    fun healthy_bdj_runtime_is_interactive_but_does_not_own_title_navigation() {
        val session = FakeBdJSession()
        val backend = BdJDiscNavigationBackend(session)

        assertEquals(DiscNavigationBackendLifecycle.Ready, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.BdJ, backend.status.menuRuntime)
        assertTrue(backend.status.interactiveMenuReady)
        assertTrue(backend.navigation.menuActive)
        assertFalse(backend.selectTitle(1))
        assertFalse(backend.selectChapter(2))
        assertTrue(backend.sendMenuCommand(PlaybackDiscMenuCommand.Select))
        assertEquals(PlaybackDiscMenuCommand.Select, session.lastCommand)
    }

    @Test
    fun bdj_exception_disables_only_optional_provider_and_clears_menu_interception() {
        val session = FakeBdJSession(failOnCommand = true)
        val backend = BdJDiscNavigationBackend(session)

        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.Down))
        assertEquals(DiscNavigationBackendLifecycle.Failed, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.None, backend.status.menuRuntime)
        assertFalse(backend.navigation.menuSupported)
        assertFalse(backend.navigation.menuActive)
        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.Select))
    }

    @Test
    fun runtime_without_authored_bdj_menu_is_not_advertised_as_ready() {
        val backend = BdJDiscNavigationBackend(FakeBdJSession(menuSupported = false))

        assertEquals(DiscNavigationBackendLifecycle.Unavailable, backend.status.lifecycle)
        assertEquals(DiscMenuRuntime.None, backend.status.menuRuntime)
        assertFalse(backend.status.interactiveMenuReady)
    }

    @Test
    fun close_is_idempotent_and_makes_the_provider_inert() {
        val session = FakeBdJSession()
        val backend = BdJDiscNavigationBackend(session)

        backend.close()
        backend.close()

        assertEquals(1, session.closeCount)
        assertEquals(DiscNavigationBackendLifecycle.Unavailable, backend.status.lifecycle)
        assertFalse(backend.sendMenuCommand(PlaybackDiscMenuCommand.Back))
    }
}

private class FakeBdJSession(
    private val failOnCommand: Boolean = false,
    private val menuSupported: Boolean = true,
) : BdJDiscSession {
    var lastCommand: PlaybackDiscMenuCommand? = null
    var closeCount = 0

    override fun navigation(): PlaybackDiscNavigationState =
        PlaybackDiscNavigationState(
            kind = PlaybackDiscKind.BluRay,
            menuSupported = menuSupported,
            menuActive = menuSupported,
        )

    override fun sendMenuCommand(command: PlaybackDiscMenuCommand): Boolean {
        if (failOnCommand) error("BD-J Xlet runtime failed")
        lastCommand = command
        return true
    }

    override fun close() {
        closeCount++
    }
}
