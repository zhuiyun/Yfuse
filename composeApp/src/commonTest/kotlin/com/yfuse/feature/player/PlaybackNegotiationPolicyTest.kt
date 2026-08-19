package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackNegotiationPolicyTest {
    @Test
    fun negotiation_budget_covers_slow_servers_without_reaching_the_global_request_timeout() {
        assertTrue(PLAYBACK_NEGOTIATION_TIMEOUT_MS >= 15_000L)
        assertTrue(PLAYBACK_NEGOTIATION_TIMEOUT_MS < 30_000L)
    }
}
