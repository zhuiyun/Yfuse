package com.yfuse.core.designsystem

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationIdentityTest {

    @Test
    fun tab_reselection_only_targets_its_own_tab() {
        val event = TabReselection(tabIdentity = "Search", occurrence = 1L)

        assertTrue(event.targets("Search"))
        assertFalse(event.targets("Home"))
        assertFalse(event.targets(null))
    }

}
