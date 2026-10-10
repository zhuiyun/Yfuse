package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPictureTapTest {
    @Test
    fun a_hidden_picture_first_reveals_controls_even_during_a_seek_burst() {
        val chrome = PlayerChromeState().apply { visible = false }
        chrome.tapPicture(false, {
            error("Hidden controls must be revealed before another seek")
        }, { error("Hidden controls must be revealed before selecting danmaku") })
        assertTrue(chrome.visible)
        assertEquals(1, chrome.interactions)
    }

    @Test
    fun loading_taps_keep_the_close_control_available_instead_of_hiding_it() {
        val chrome = PlayerChromeState()
        repeat(2) {
            chrome.tapPicture(
                true,
                { error("A loading tap must not seek") },
                { error("A loading tap must not select danmaku") },
            )
            assertTrue(chrome.visible)
        }
        assertEquals(2, chrome.interactions)
    }

    @Test
    fun dismissing_a_panel_restarts_the_controls_timer_without_seeking() {
        val chrome = PlayerChromeState().apply { quickPopup = QuickPopup.Speed }
        chrome.tapPicture(false, {
            error("Panel dismissal must not seek")
        }, { error("Panel dismissal must not select danmaku") })
        assertEquals(null, chrome.quickPopup)
        assertTrue(chrome.visible)
        assertEquals(1, chrome.interactions)
    }

    @Test
    fun the_lock_keeps_seek_and_danmaku_actions_out() {
        val chrome =
            PlayerChromeState().apply {
                locked = true
                visible = false
            }
        chrome.tapPicture(true, {
            error("A locked picture must not seek")
        }, { error("A locked picture must not select danmaku") })
        assertTrue(chrome.locked)
        assertFalse(chrome.visible)
        assertEquals(1, chrome.lockedRevealRevision)
    }

    @Test
    fun visible_playback_still_accepts_seek_bursts_and_danmaku_before_hiding() {
        val chrome = PlayerChromeState()
        var seeks = 0
        var picks = 0
        chrome.tapPicture(false, {
            seeks++
            true
        }, { error("A seek tap must not select danmaku") })
        assertTrue(chrome.visible)
        chrome.tapPicture(false, { false }, {
            picks++
            true
        })
        assertTrue(chrome.visible)
        chrome.tapPicture(false, { false }, { false })
        assertFalse(chrome.visible)
        assertEquals(1, seeks)
        assertEquals(1, picks)
    }
}
