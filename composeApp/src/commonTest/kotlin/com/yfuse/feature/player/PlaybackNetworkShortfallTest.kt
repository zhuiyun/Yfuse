package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackNetworkShortfallTest {
    @Test
    fun a_link_slower_than_the_source_is_named_with_both_rates() {
        // The evening's third title: 13.3 Mbps over a server delivering about 2 Mbps.
        assertEquals("网速约 2.1 Mbps，低于片源 13.3 Mbps", networkShortfallMessage(2_080_000L, 13_347_310L))
    }

    @Test
    fun a_link_that_keeps_up_says_nothing() {
        assertNull(networkShortfallMessage(18_000_000L, 6_224_456L))
        assertFalse(networkCannotCarrySource(6_224_456L, 6_224_456L))
    }

    @Test
    fun an_unknown_rate_is_never_reported_as_a_slow_network() {
        assertNull(networkShortfallMessage(0L, 13_347_310L))
        assertNull(networkShortfallMessage(2_000_000L, 0L))
        assertTrue(networkCannotCarrySource(1L, 2L))
    }
}
