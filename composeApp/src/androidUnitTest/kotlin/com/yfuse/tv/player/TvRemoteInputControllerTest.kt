package com.yfuse.tv.player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TvRemoteInputControllerTest {
    @Test
    fun center_toggles_and_reveals_hidden_chrome_but_defers_to_a_focused_control() {
        val harness = Harness()

        assertTrue(harness.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, timeMs = 1_000L))
        assertEquals(1, harness.toggles)
        assertEquals(TvPlayerChromeLayer.Controls, harness.chrome.state.value.layer)
        assertTrue(harness.keyUp(KeyEvent.KEYCODE_DPAD_CENTER, timeMs = 1_010L))

        harness.chrome.publishUiState(
            layer = TvPlayerChromeLayer.Controls,
            panel = null,
            controlsHaveFocus = true,
        )
        assertFalse(harness.keyDown(KeyEvent.KEYCODE_DPAD_CENTER, timeMs = 1_020L))
        assertEquals(1, harness.toggles)
    }

    @Test
    fun hidden_dpad_seek_is_bounded_debounced_and_flushed_on_release() {
        val harness = Harness(positionMs = 50_000L, durationMs = 72_000L)

        assertTrue(harness.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, timeMs = 1_000L))
        assertEquals(listOf(60_000L), harness.seeks)
        assertEquals(60_000L, harness.chrome.state.value.seekTargetMs)

        harness.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 1, timeMs = 1_100L)
        assertEquals(listOf(60_000L), harness.seeks)
        assertEquals(63_000L, harness.chrome.state.value.seekTargetMs)

        harness.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 2, timeMs = 1_260L)
        assertEquals(listOf(60_000L, 66_000L), harness.seeks)

        // After three seconds the held step ramps to nine seconds, still clamped to duration.
        harness.keyDown(KeyEvent.KEYCODE_DPAD_RIGHT, repeat = 3, timeMs = 4_100L)
        assertEquals(listOf(60_000L, 66_000L, 72_000L), harness.seeks)
        assertTrue(harness.keyUp(KeyEvent.KEYCODE_DPAD_RIGHT, timeMs = 4_120L))
        assertFalse(harness.chrome.state.value.seeking)
        assertNull(harness.chrome.state.value.seekTargetMs)
    }

    @Test
    fun release_flushes_a_repeat_that_arrived_inside_the_debounce_window() {
        val harness = Harness(positionMs = 20_000L, durationMs = 90_000L)

        harness.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, timeMs = 2_000L)
        harness.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1, timeMs = 2_100L)
        assertEquals(listOf(10_000L), harness.seeks)

        harness.keyUp(KeyEvent.KEYCODE_DPAD_LEFT, timeMs = 2_120L)
        assertEquals(listOf(10_000L, 7_000L), harness.seeks)
    }

    @Test
    fun visible_chrome_leaves_fresh_dpad_arrows_to_compose_navigation() {
        val harness = Harness()
        harness.chrome.publishUiState(
            layer = TvPlayerChromeLayer.Controls,
            panel = null,
            controlsHaveFocus = true,
        )

        assertFalse(harness.keyDown(KeyEvent.KEYCODE_DPAD_LEFT, timeMs = 1_000L))
        assertFalse(harness.keyDown(KeyEvent.KEYCODE_DPAD_DOWN, timeMs = 1_010L))
        assertTrue(harness.seeks.isEmpty())
    }

    @Test
    fun space_is_play_pause_over_video_but_remains_text_input_inside_a_panel() {
        val harness = Harness()

        assertTrue(harness.keyDown(KeyEvent.KEYCODE_SPACE, timeMs = 1_000L))
        assertEquals(1, harness.toggles)

        harness.chrome.publishUiState(
            layer = TvPlayerChromeLayer.Panel,
            panel = TvPlayerChromePanel.WatchChat,
            controlsHaveFocus = true,
        )
        assertFalse(harness.keyDown(KeyEvent.KEYCODE_SPACE, timeMs = 1_010L))
        assertEquals(1, harness.toggles)
    }

    @Test
    fun media_keys_have_explicit_play_pause_and_queue_semantics() {
        val harness = Harness()

        assertTrue(harness.keyDown(KeyEvent.KEYCODE_MEDIA_PLAY, timeMs = 1_000L))
        assertTrue(harness.keyUp(KeyEvent.KEYCODE_MEDIA_PLAY, timeMs = 1_010L))
        assertTrue(harness.keyDown(KeyEvent.KEYCODE_MEDIA_PAUSE, timeMs = 1_020L))
        assertTrue(harness.keyDown(KeyEvent.KEYCODE_MEDIA_STOP, timeMs = 1_030L))
        assertTrue(harness.keyDown(KeyEvent.KEYCODE_MEDIA_PREVIOUS, timeMs = 1_040L))
        assertTrue(harness.keyDown(KeyEvent.KEYCODE_MEDIA_NEXT, timeMs = 1_050L))

        assertEquals(1, harness.plays)
        assertEquals(2, harness.pauses)
        assertEquals(1, harness.previous)
        assertEquals(1, harness.next)
    }

    private class Harness(
        private var positionMs: Long = 30_000L,
        private var durationMs: Long = 120_000L,
    ) {
        val chrome = TvPlayerChromeController()
        val seeks = mutableListOf<Long>()
        var toggles = 0
        var plays = 0
        var pauses = 0
        var previous = 0
        var next = 0
        private var clockMs = 1L
        private val controller =
            TvRemoteInputController(
                chrome = chrome,
                playback =
                    TvPlaybackActions(
                        currentPositionMs = { positionMs },
                        durationMs = { durationMs },
                        togglePlayPause = { toggles++ },
                        play = { plays++ },
                        pause = { pauses++ },
                        seekTo = {
                            positionMs = it
                            seeks += it
                        },
                        previous = { previous++ },
                        next = { next++ },
                    ),
                nowMs = { clockMs },
            )

        fun keyDown(
            keyCode: Int,
            repeat: Int = 0,
            timeMs: Long,
        ): Boolean {
            clockMs = timeMs
            return controller.dispatchKey(KeyEvent.ACTION_DOWN, keyCode, repeat, timeMs)
        }

        fun keyUp(
            keyCode: Int,
            timeMs: Long,
        ): Boolean {
            clockMs = timeMs
            return controller.dispatchKey(KeyEvent.ACTION_UP, keyCode, eventTime = timeMs)
        }
    }
}
