package com.yfuse.app

import com.yfuse.app.RootComponent.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TopLevelBackStackTest {
    @Test
    fun home_is_the_only_system_exit_root() {
        assertEquals(listOf(Tab.Home), topLevelBackStack(Tab.Home))
    }

    @Test
    fun every_other_root_previews_home() {
        assertEquals(listOf(Tab.Home, Tab.Browse), topLevelBackStack(Tab.Browse))
        assertEquals(listOf(Tab.Home, Tab.Search), topLevelBackStack(Tab.Search))
        assertEquals(listOf(Tab.Home, Tab.Profile), topLevelBackStack(Tab.Profile))
    }

    @Test
    fun a_saved_tab_is_restored_by_name() {
        Tab.entries.forEach { tab -> assertEquals(tab, restoredTab(tab.name)) }
    }

    @Test
    fun no_saved_tab_or_an_unknown_one_falls_back_to_the_startup_rule() {
        assertNull(restoredTab(null))
        assertNull(restoredTab("Downloads"))
    }
}
