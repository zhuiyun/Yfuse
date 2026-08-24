package com.yfuse.core.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleFlightNavigationGuardTest {
    @Test
    fun overlapping_navigation_is_rejected_until_the_first_route_materializes() {
        val guard = SingleFlightNavigationGuard<String>()

        assertTrue(guard.tryBegin(target = "player-a", active = null))
        assertFalse(guard.tryBegin(target = "player-a", active = null))
        assertFalse(guard.tryBegin(target = "player-b", active = null))

        guard.complete("player-a")
        assertFalse(guard.tryBegin(target = "player-a", active = "player-a"))
        assertTrue(guard.tryBegin(target = "player-a", active = null))
    }
}
