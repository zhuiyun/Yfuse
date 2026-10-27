package com.yfuse.feature.widgets

import com.russhwolf.settings.MapSettings
import com.yfuse.app.RootComponent.Tab
import com.yfuse.app.navigationTabs
import com.yfuse.app.topLevelBackStack
import com.yfuse.core.data.ThemePreferences
import com.yfuse.core.widget.WidgetDatabase
import com.yfuse.core.widget.WidgetPage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomHomeNavigationTest {
    @Test
    fun selected_home_and_page_survive_a_new_preferences_instance() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        preferences.rememberHome(true)
        preferences.customHomePageId = "my-series"
        val reopened = ThemePreferences(settings)
        assertTrue(reopened.resumeCustomHome)
        assertEquals("my-series", reopened.customHomePageId)
        reopened.rememberHome(false)
        assertFalse(ThemePreferences(settings).resumeCustomHome)
        reopened.rememberHome(true)
        reopened.setStartupTab(com.yfuse.core.model.StartupTab.Servers)
        assertFalse(ThemePreferences(settings).resumeCustomHome)
    }

    @Test
    fun visibility_persists_and_only_removes_the_custom_destination() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        assertTrue(preferences.customHomeVisible.value)
        preferences.setCustomHomeVisible(false)
        assertFalse(ThemePreferences(settings).customHomeVisible.value)
        assertEquals(navigationTabs(true).filterNot { it.tab == Tab.CustomHome }, navigationTabs(false))
        assertEquals(listOf(Tab.Browse, Tab.CustomHome), topLevelBackStack(Tab.CustomHome, Tab.Browse))
    }

    @Test
    fun widget_metadata_details_return_to_custom_home_instead_of_the_start_tab() {
        assertEquals(
            listOf(Tab.CustomHome, Tab.Home),
            topLevelBackStack(Tab.Home, start = Tab.Servers, discoveryOrigin = Tab.CustomHome),
        )
    }

    @Test
    fun hidden_or_deleted_selected_page_falls_back_without_changing_saved_data() {
        val first = WidgetPage("first", "精选")
        val second = WidgetPage("second", "追剧")
        val hidden = WidgetPage("hidden", "旧页面", hidden = true)
        val database = WidgetDatabase(pages = listOf(first, second, hidden), defaultPageId = second.id)
        assertEquals(first, customHomePage(database, first.id))
        assertEquals(second, customHomePage(database, hidden.id))
        assertEquals(second, customHomePage(database, "deleted"))
        assertEquals(first, customHomePage(database.copy(defaultPageId = hidden.id), null))
        assertNull(customHomePage(database.copy(pages = listOf(hidden)), hidden.id))
    }

    @Test
    fun wallpaper_dim_is_not_forced_to_an_opaque_floor() {
        val settings = MapSettings()
        val preferences = ThemePreferences(settings)
        preferences.setBackgroundDim(0.18f)
        assertEquals(0.18f, ThemePreferences(settings).backgroundDim.value)
        preferences.setBackgroundDim(0f)
        assertEquals(0f, ThemePreferences(settings).backgroundDim.value)
    }
}
