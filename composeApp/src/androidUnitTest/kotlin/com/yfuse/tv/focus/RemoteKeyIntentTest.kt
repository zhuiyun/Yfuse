package com.yfuse.tv.focus

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteKeyIntentTest {
    @Test
    fun mapsDpadDirectionsAndPreservesRepeatInformation() {
        val first =
            RemoteIntentPolicy.map(
                RemoteKeyInput(RemotePhysicalKey.DirectionLeft, RemoteKeyPhase.Down),
            )
        val repeated =
            RemoteIntentPolicy.map(
                RemoteKeyInput(
                    RemotePhysicalKey.DirectionRight,
                    RemoteKeyPhase.Down,
                    repeatCount = 4,
                ),
            )

        assertEquals(
            RemoteIntent.Navigate(TvFocusDirection.Left, repeated = false),
            first,
        )
        val repeatedNavigation = assertIs<RemoteIntent.Navigate>(repeated)
        assertEquals(TvFocusDirection.Right, repeatedNavigation.direction)
        assertTrue(repeatedNavigation.repeated)
    }

    @Test
    fun activateFiresOnceAndLongPressOpensContextMenu() {
        assertEquals(
            RemoteIntent.Activate,
            RemoteIntentPolicy.map(
                RemoteKeyInput(RemotePhysicalKey.Activate, RemoteKeyPhase.Up),
            ),
        )
        assertNull(
            RemoteIntentPolicy.map(
                RemoteKeyInput(
                    RemotePhysicalKey.Activate,
                    RemoteKeyPhase.Down,
                    repeatCount = 1,
                ),
            ),
        )
        assertEquals(
            RemoteIntent.OpenContextMenu,
            RemoteIntentPolicy.map(
                RemoteKeyInput(
                    RemotePhysicalKey.Activate,
                    RemoteKeyPhase.Down,
                    repeatCount = 1,
                    isLongPress = true,
                ),
            ),
        )
        assertNull(
            RemoteIntentPolicy.map(
                RemoteKeyInput(RemotePhysicalKey.Activate, RemoteKeyPhase.Down),
            ),
        )
    }

    @Test
    fun androidMapperCoversTvKeyboardGamepadAndMediaKeys() {
        assertEquals(
            RemoteIntent.Activate,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP),
        )
        assertEquals(
            RemoteIntent.Activate,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP),
        )
        assertEquals(
            RemoteIntent.Back,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_ESCAPE, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            RemoteIntent.PlayPause,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            RemoteIntent.PlayPause,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            RemoteIntent.Stop,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.ACTION_DOWN),
        )
        assertEquals(
            RemoteIntent.Captions,
            AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_CAPTIONS, KeyEvent.ACTION_DOWN),
        )
        assertNull(AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN))
        assertNull(AndroidRemoteKeyMapper.intent(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.ACTION_MULTIPLE))
    }
}
