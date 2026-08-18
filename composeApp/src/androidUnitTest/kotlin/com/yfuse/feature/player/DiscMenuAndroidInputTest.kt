package com.yfuse.feature.player

import android.view.KeyEvent
import com.yfuse.core.playback.PlaybackDiscMenuCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DiscMenuAndroidInputTest {
    @Test
    fun dpad_enter_and_menu_keys_map_to_backend_neutral_commands() {
        assertEquals(PlaybackDiscMenuCommand.Up, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PlaybackDiscMenuCommand.Down, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PlaybackDiscMenuCommand.Left, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PlaybackDiscMenuCommand.Right, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PlaybackDiscMenuCommand.Select, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(PlaybackDiscMenuCommand.Select, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_ENTER))
        assertEquals(PlaybackDiscMenuCommand.Select, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(PlaybackDiscMenuCommand.ShowMenu, discMenuCommandForAndroidKey(KeyEvent.KEYCODE_MENU))
        assertNull(discMenuCommandForAndroidKey(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
