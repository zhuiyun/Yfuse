package com.yfuse.feature.history

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryResumeTest {
    @Test fun completedAndInvalidPositionsStartAtZero() {
        assertEquals(0L, historyResumeTicks(50_000L, true))
        assertEquals(0L, historyResumeTicks(-1L, false))
    }

    @Test fun tickConversionCannotOverflow() {
        assertEquals(600_000_000L, historyResumeTicks(60_000L, false))
        assertEquals(Long.MAX_VALUE / 10_000 * 10_000, historyResumeTicks(Long.MAX_VALUE, false))
    }
}
