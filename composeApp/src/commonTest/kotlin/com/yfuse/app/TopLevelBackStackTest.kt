package com.yfuse.app

import com.yfuse.app.RootComponent.Tab
import com.yfuse.core.model.StartupTab
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

    @Test
    fun automatic_start_opens_servers_until_there_is_one_and_the_library_after() {
        assertEquals(Tab.Servers, startupTab(StartupTab.Automatic, hasServers = false))
        assertEquals(Tab.Browse, startupTab(StartupTab.Automatic, hasServers = true))
    }

    @Test
    fun a_chosen_start_tab_holds_with_or_without_servers() {
        listOf(false, true).forEach { hasServers ->
            assertEquals(Tab.Home, startupTab(StartupTab.Home, hasServers))
            assertEquals(Tab.Browse, startupTab(StartupTab.Library, hasServers))
            assertEquals(Tab.Servers, startupTab(StartupTab.Servers, hasServers))
        }
    }
}
