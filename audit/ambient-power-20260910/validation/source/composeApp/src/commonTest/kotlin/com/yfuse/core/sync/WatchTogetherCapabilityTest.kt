package com.yfuse.core.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherCapabilityTest {
    @Test
    fun legacy_welcome_without_capabilities_disables_reaction_sends() {
        assertFalse(supportsWatchReactions(null))
        assertFalse(supportsWatchReactions(emptyList()))
        assertFalse(
            WatchTogetherState(
                connected = true,
                reactionsSupported = supportsWatchReactions(null),
            ).canSendReaction(),
        )
    }

    @Test
    fun advertised_reaction_capability_enables_sends_only_on_a_live_connection() {
        assertTrue(supportsWatchReactions(listOf(WATCH_CAPABILITY_REACTIONS)))
        val state = WatchTogetherState(connected = true, reactionsSupported = true)
        assertTrue(state.canSendReaction())
        assertFalse(state.copy(reconnecting = true).canSendReaction())
        assertFalse(state.copy(connected = false).canSendReaction())
    }
}
