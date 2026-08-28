package com.yfuse.core.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleFlightNavigationGuardTest {
    @Test
    fun overlapping_navigation_is_rejected_until_the_destination_route_is_removed() {
        val guard = SingleFlightNavigationGuard<String>()

        assertTrue(guard.tryBegin(target = "player-a", active = null))
        assertFalse(guard.tryBegin(target = "player-a", active = null))
        assertFalse(guard.tryBegin(target = "player-b", active = null))
        assertFalse(guard.tryBegin(target = "player-b", active = "player-a"))

        // Release only after popping the Player route. The next detail-page launch can then
        // begin normally; releasing during child construction recreated the duplicate race.
        guard.complete("player-a")
        assertTrue(guard.tryBegin(target = "player-a", active = null))
    }
}
