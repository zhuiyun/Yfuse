package com.yfuse.feature.player

import com.yfuse.core.playback.PlaybackDiscMenuCommand
import com.yfuse.core.playback.PlaybackDiscNavigationState
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class NativeBluRaySessionRegistryTest {
    @Test
    fun stale_disc_callbacks_cannot_clear_the_current_ycore_menu() {
        val old = Session()
        val current = Session()
        val frame = NativeBluRayOverlayFrame(1, 1, intArrayOf(0xff102030.toInt()))
        try {
            NativeRemoteBluRaySessionRegistry.activate(old)
            NativeRemoteBluRaySessionRegistry.activate(current)
            NativeRemoteBluRaySessionRegistry.updateOverlay(current, frame)

            NativeRemoteBluRaySessionRegistry.updateOverlay(old, null)
            NativeRemoteBluRaySessionRegistry.deactivate(old)

            assertSame(current, NativeRemoteBluRaySessionRegistry.activeSession.value)
            assertSame(frame, NativeRemoteBluRaySessionRegistry.overlay.value)
        } finally {
            NativeRemoteBluRaySessionRegistry.deactivate(current)
            NativeRemoteBluRaySessionRegistry.deactivate(old)
        }
    }

    @Test
    fun changing_or_closing_the_disc_removes_its_authored_overlay() {
        val first = Session()
        val second = Session()
        try {
            NativeRemoteBluRaySessionRegistry.activate(first)
            NativeRemoteBluRaySessionRegistry.updateOverlay(first, NativeBluRayOverlayFrame(1, 1, intArrayOf(1)))
            NativeRemoteBluRaySessionRegistry.activate(second)
            assertNull(NativeRemoteBluRaySessionRegistry.overlay.value)

            NativeRemoteBluRaySessionRegistry.updateOverlay(second, NativeBluRayOverlayFrame(1, 1, intArrayOf(2)))
            NativeRemoteBluRaySessionRegistry.deactivate(second)
            assertNull(NativeRemoteBluRaySessionRegistry.activeSession.value)
            assertNull(NativeRemoteBluRaySessionRegistry.overlay.value)
        } finally {
            NativeRemoteBluRaySessionRegistry.deactivate(second)
            NativeRemoteBluRaySessionRegistry.deactivate(first)
        }
    }

    private class Session : HdmvDiscSession {
        override fun navigation() = PlaybackDiscNavigationState()

        override fun selectTitle(index: Int) = false

        override fun selectChapter(index: Int) = false

        override fun sendMenuCommand(command: PlaybackDiscMenuCommand) = false

        override fun close() = Unit
    }
}
