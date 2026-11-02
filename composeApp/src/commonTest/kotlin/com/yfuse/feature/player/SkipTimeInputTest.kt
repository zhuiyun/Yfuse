package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Regression coverage for the editable intro/credits clock fields. */
class SkipTimeInputTest {
    @Test
    fun credits_input_is_seconds_remaining_not_an_absolute_timestamp() {
        assertEquals(90L, parseCreditsLeadInput("90", 2_700L))
        assertEquals(90L, parseCreditsLeadInput("90", 0L))
        assertEquals(0L, parseCreditsLeadInput("0", 2_700L))
        assertNull(parseCreditsLeadInput("43:30", 2_700L))
        assertNull(parseCreditsLeadInput("2700", 2_700L))
        assertNull(parseCreditsLeadInput("-1", 2_700L))
        assertNull(parseCreditsLeadInput("36001", 0L))
    }

    @Test
    fun parser_acceptsSecondsMinuteAndHourClocks() {
        assertEquals(75L, parseSkipTimestamp("75"))
        assertEquals(75L, parseSkipTimestamp("1:15"))
        assertEquals(3_723L, parseSkipTimestamp("1:02:03"))
        assertEquals(75L, parseSkipTimestamp("1：15"))
    }

    @Test
    fun parser_rejectsInvalidClockFields() {
        assertNull(parseSkipTimestamp("1:60"))
        assertNull(parseSkipTimestamp("1:2:60"))
        assertNull(parseSkipTimestamp("a:12"))
    }

    @Test
    fun creditsEditorConvertsAbsoluteTimestampToStoredLead() {
        assertEquals(120L, creditsLeadSecondsFromStart(2_580L, 2_700L))
        assertEquals(2_580L, creditsStartSecondsFromLead(120L, 2_700L))
        assertNull(creditsLeadSecondsFromStart(2_700L, 2_700L))
    }
}
