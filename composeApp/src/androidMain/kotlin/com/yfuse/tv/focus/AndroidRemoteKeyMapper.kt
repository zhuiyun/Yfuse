package com.yfuse.tv.focus

import android.view.KeyEvent

/** Android boundary for the otherwise platform-independent remote intent policy. */
object AndroidRemoteKeyMapper {
    fun physicalKey(keyCode: Int): RemotePhysicalKey? =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> RemotePhysicalKey.DirectionUp
            KeyEvent.KEYCODE_DPAD_DOWN -> RemotePhysicalKey.DirectionDown
            KeyEvent.KEYCODE_DPAD_LEFT -> RemotePhysicalKey.DirectionLeft
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemotePhysicalKey.DirectionRight
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
            -> RemotePhysicalKey.Activate

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_B,
            -> RemotePhysicalKey.Back

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_Y,
            -> RemotePhysicalKey.Menu

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> RemotePhysicalKey.PlayPause

            KeyEvent.KEYCODE_MEDIA_PLAY -> RemotePhysicalKey.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> RemotePhysicalKey.Pause
            KeyEvent.KEYCODE_MEDIA_STOP -> RemotePhysicalKey.Stop
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemotePhysicalKey.FastForward
            KeyEvent.KEYCODE_MEDIA_REWIND -> RemotePhysicalKey.Rewind
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP,
            -> RemotePhysicalKey.Next

            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            -> RemotePhysicalKey.Previous

            KeyEvent.KEYCODE_SEARCH -> RemotePhysicalKey.Search
            KeyEvent.KEYCODE_INFO -> RemotePhysicalKey.Info
            KeyEvent.KEYCODE_CAPTIONS -> RemotePhysicalKey.Captions
            KeyEvent.KEYCODE_GUIDE -> RemotePhysicalKey.Guide
            else -> null
        }

    fun input(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
        isLongPress: Boolean = false,
    ): RemoteKeyInput? {
        val physical = physicalKey(keyCode) ?: return null
        val phase =
            when (action) {
                KeyEvent.ACTION_DOWN -> RemoteKeyPhase.Down
                KeyEvent.ACTION_UP -> RemoteKeyPhase.Up
                else -> return null
            }
        return RemoteKeyInput(
            key = physical,
            phase = phase,
            repeatCount = repeatCount.coerceAtLeast(0),
            isLongPress = isLongPress,
        )
    }

    fun intent(
        keyCode: Int,
        action: Int,
        repeatCount: Int = 0,
        isLongPress: Boolean = false,
    ): RemoteIntent? =
        input(
            keyCode = keyCode,
            action = action,
            repeatCount = repeatCount,
            isLongPress = isLongPress,
        )?.let(RemoteIntentPolicy::map)
}
