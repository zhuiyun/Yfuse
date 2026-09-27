package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PauseInfoTest {
    private fun eligible(
        playing: Boolean = false,
        buffering: Boolean = false,
        ended: Boolean = false,
        failed: Boolean = false,
        controlsVisible: Boolean = false,
        overlayOpen: Boolean = false,
        locked: Boolean = false,
    ) = pauseInfoEligible(playing, buffering, ended, failed, controlsVisible, overlayOpen, locked)

    @Test
    fun only_a_settled_pause_with_the_chrome_away_qualifies() {
        assertTrue(eligible())
        assertFalse(eligible(playing = true))
        assertFalse(eligible(buffering = true))
        assertFalse(eligible(ended = true))
        assertFalse(eligible(failed = true))
        assertFalse(eligible(controlsVisible = true))
        assertFalse(eligible(overlayOpen = true))
        assertFalse(eligible(locked = true))
    }

    @Test
    fun the_layer_waits_three_seconds() {
        assertEquals(3_000L, PAUSE_INFO_DELAY_MS)
    }

    @Test
    fun lines_are_the_chapter_a_few_names_and_the_end() {
        val info = pauseInfo("  重逢 ", listOf("甲", "乙", " ", "乙", "丙", "丁"), "结束于 21:47")
        assertEquals(PauseInfo(chapter = "重逢", cast = "主演 甲、乙、丙", endsAt = "结束于 21:47"), info)
    }

    @Test
    fun missing_lines_are_skipped_and_an_empty_layer_is_none() {
        val endOnly = pauseInfo(null, emptyList(), "结束于 21:47")
        assertEquals(PauseInfo(chapter = null, cast = null, endsAt = "结束于 21:47"), endOnly)
        assertEquals(PauseInfo(chapter = "序章", cast = null, endsAt = null), pauseInfo("序章", listOf(" "), null))
        assertNull(pauseInfo(" ", emptyList(), null))
        assertNull(pauseInfo(null, emptyList(), " "))
    }
}
