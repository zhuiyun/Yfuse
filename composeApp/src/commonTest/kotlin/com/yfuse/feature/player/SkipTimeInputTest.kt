package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipTimeInputTest {
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
