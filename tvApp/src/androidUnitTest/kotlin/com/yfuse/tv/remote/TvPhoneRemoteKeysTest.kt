package com.yfuse.tv.remote

import android.text.InputType
import android.view.KeyEvent
import com.yfuse.watch.protocol.RemoteControlKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun a_phone_never_types_into_a_password_field() {
        val text = InputType.TYPE_CLASS_TEXT
        // What a TV secret field (KeyboardType.Password) and platform password fields report.
        assertTrue(isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        // Flags such as no-suggestions do not hide the variation.
        assertTrue(
            isPasswordInputType(
                text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            ),
        )
        // Search, addresses, names and plain numbers are typed as before.
        assertFalse(isPasswordInputType(text))
        assertFalse(isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_URI))
        assertFalse(isPasswordInputType(text or InputType.TYPE_TEXT_VARIATION_PERSON_NAME))
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER))
        // A number field's variation bits mean something else in the text class, and the reverse.
        assertFalse(isPasswordInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertFalse(isPasswordInputType(InputType.TYPE_NULL))
    }
}
