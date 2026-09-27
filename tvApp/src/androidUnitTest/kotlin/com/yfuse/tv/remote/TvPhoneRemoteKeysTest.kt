package com.yfuse.tv.remote

import android.view.KeyEvent
import com.yfuse.watch.protocol.RemoteControlKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvPhoneRemoteKeysTest {
    @Test
    fun phone_keys_become_the_keys_a_physical_remote_sends() {
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, remoteKeyCode(RemoteControlKey.Up))
        assertEquals(KeyEvent.KEYCODE_DPAD_DOWN, remoteKeyCode(RemoteControlKey.Down))
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, remoteKeyCode(RemoteControlKey.Left))
        assertEquals(KeyEvent.KEYCODE_DPAD_RIGHT, remoteKeyCode(RemoteControlKey.Right))
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, remoteKeyCode(RemoteControlKey.Center))
        assertEquals(KeyEvent.KEYCODE_BACK, remoteKeyCode(RemoteControlKey.Back))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, remoteKeyCode(RemoteControlKey.PlayPause))
        // Apps never receive Home; 主页 is answered inside the app instead.
        assertNull(remoteKeyCode(RemoteControlKey.Home))
    }
}
