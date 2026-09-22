package com.yfuse.app

import com.yfuse.app.RootComponent.Tab
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
