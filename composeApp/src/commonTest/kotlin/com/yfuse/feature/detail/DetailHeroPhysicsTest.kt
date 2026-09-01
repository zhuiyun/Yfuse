package com.yfuse.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DetailHeroPhysicsTest {
    @Test
    fun rubber_band_resistance_increases_with_pull_distance() {
        val near = resistedOverscrollDelta(0f, 40f, 320f)
        val far = resistedOverscrollDelta(320f, 40f, 320f)

        assertTrue(near > far)
        assertEquals(near / 4f, far, absoluteTolerance = 0.0001f)
    }

    @Test
    fun non_downward_travel_does_not_extend_the_pull() {
        assertEquals(0f, resistedOverscrollDelta(80f, -20f, 320f))
    }
}
