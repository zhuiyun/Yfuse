package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PosterRatingTest {
    @Test
    fun formatsValidScoresToOneDecimalPlace() {
        assertEquals("7.8", mediaRatingLabel(7.84))
        assertEquals("10.0", mediaRatingLabel(11.0))
    }

    @Test
    fun hidesMissingOrInvalidScores() {
        assertNull(mediaRatingLabel(null))
        assertNull(mediaRatingLabel(0.0))
        assertNull(mediaRatingLabel(Double.NaN))
    }
}
