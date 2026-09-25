package com.yfuse.core.designsystem

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.ThemePreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class MotionThemeTest {
    @Test
    fun the_theme_persists_by_name_and_defaults_to_classic() {
        val settings = MapSettings()
        assertEquals(MotionTheme.Classic, ThemePreferences(settings).motionTheme.value)
        for (theme in MotionTheme.entries) {
            ThemePreferences(settings).setMotionTheme(theme)
            assertEquals(theme, ThemePreferences(settings).motionTheme.value)
        }
        settings.putString("appearance.motionTheme", "future-theme")
        assertEquals(MotionTheme.Classic, ThemePreferences(settings).motionTheme.value)
    }
}
