package com.yfuse.tv.focus

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun longPressLandsOnTheFirstRepeatSixHundredMillisecondsIn() {
        val clock = RemoteLongPressClock()
        val center = KeyEvent.KEYCODE_DPAD_CENTER

        assertFalse(clock.onDown(center, repeatCount = 0, eventTimeMs = 1_000L))
        // The platform's own flag arrives with the first repeat, 400-500 ms in: too early.
        assertFalse(clock.onDown(center, repeatCount = 1, eventTimeMs = 1_450L))
        assertFalse(clock.onDown(center, repeatCount = 4, eventTimeMs = 1_599L))
        assertTrue(clock.onDown(center, repeatCount = 5, eventTimeMs = 1_600L))
        // Once per hold: the repeats after it are not long presses again.
        assertFalse(clock.onDown(center, repeatCount = 6, eventTimeMs = 1_650L))
        assertFalse(clock.onDown(center, repeatCount = 20, eventTimeMs = 2_400L))
    }

    @Test
    fun longPressIsTimedFromTheFirstPressNotFromEachRepeat() {
        // A remote whose driver repeats keys itself stamps every repeat as a fresh press, so
        // only the first key-down's own time can say how long the key has been held.
        val clock = RemoteLongPressClock()
        val enter = KeyEvent.KEYCODE_ENTER
        clock.onDown(enter, repeatCount = 0, eventTimeMs = 0L)
        assertFalse(clock.onDown(enter, repeatCount = 1, eventTimeMs = 250L))
        assertFalse(clock.onDown(enter, repeatCount = 2, eventTimeMs = 360L))
        assertTrue(clock.onDown(enter, repeatCount = 5, eventTimeMs = 690L))
    }

    @Test
    fun aReleaseOrAFreshPressStartsTheClockAgain() {
        val clock = RemoteLongPressClock()
        val center = KeyEvent.KEYCODE_DPAD_CENTER
        clock.onDown(center, repeatCount = 0, eventTimeMs = 0L)
        assertTrue(clock.onDown(center, repeatCount = 3, eventTimeMs = 700L))
        clock.onUp(center)

        clock.onDown(center, repeatCount = 0, eventTimeMs = 5_000L)
        assertFalse(clock.onDown(center, repeatCount = 1, eventTimeMs = 5_300L))
        assertTrue(clock.onDown(center, repeatCount = 4, eventTimeMs = 5_620L))
    }

    @Test
    fun aHoldFirstSeenMidwayIsTimedFromWhereItWasFirstSeen() {
        // Its first key-down went to another surface; this one has only its repeats.
        val clock = RemoteLongPressClock()
        val center = KeyEvent.KEYCODE_DPAD_CENTER
        assertFalse(clock.onDown(center, repeatCount = 9, eventTimeMs = 900L))
        assertFalse(clock.onDown(center, repeatCount = 12, eventTimeMs = 1_400L))
        assertTrue(clock.onDown(center, repeatCount = 14, eventTimeMs = 1_500L))
    }

    @Test
    fun aPanelIgnoresTheRestOfTheHoldThatOpenedIt() {
        val carryOver = RemoteHoldCarryOver()
        val center = KeyEvent.KEYCODE_DPAD_CENTER

        // The opening hold's repeats and release would otherwise click the panel's first row.
        assertTrue(carryOver.swallow(center, down = true, repeatCount = 12))
        assertTrue(carryOver.swallow(center, down = true, repeatCount = 13))
        assertTrue(carryOver.swallow(center, down = false, repeatCount = 0))

        // A fresh press is the person choosing a row, with its own repeats and release.
        assertFalse(carryOver.swallow(center, down = true, repeatCount = 0))
        assertFalse(carryOver.swallow(center, down = true, repeatCount = 1))
        assertFalse(carryOver.swallow(center, down = false, repeatCount = 0))
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
