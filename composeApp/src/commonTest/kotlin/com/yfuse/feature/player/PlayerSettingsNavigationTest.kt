package com.yfuse.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerSettingsNavigationTest {
    @Test
    fun nested_settings_return_to_playback_before_the_more_menu() {
        for (page in listOf(AdvancedPage.Picture, AdvancedPage.Gestures, AdvancedPage.SleepTimer)) {
            assertEquals(AdvancedPage.Playback, page.parentPage)
            assertEquals(AdvancedPage.Root, page.parentPage.parentPage)
        }
        assertEquals(AdvancedPage.Root, AdvancedPage.Engine.parentPage)
        assertEquals(AdvancedPage.Root, AdvancedPage.Media.parentPage)
    }
}
