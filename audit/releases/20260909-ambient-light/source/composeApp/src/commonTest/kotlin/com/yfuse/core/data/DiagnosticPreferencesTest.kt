package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticPreferencesTest {
    @Test
    fun logcat_output_defaults_off_persists_and_expires_after_one_hour() {
        val settings = MapSettings()
        var now = 1_000L
        val preferences = DiagnosticPreferences(settings) { now }

        assertFalse(preferences.logcatEnabled.value)

        preferences.setLogcatEnabled(true)
        assertTrue(DiagnosticPreferences(settings) { now }.logcatEnabled.value)

        now += LOGCAT_OUTPUT_WINDOW_MS + 1L
        assertFalse(preferences.isLogcatEnabledNow())
        assertFalse(DiagnosticPreferences(settings) { now }.logcatEnabled.value)

        preferences.setLogcatEnabled(true)
        preferences.setLogcatEnabled(false)
        assertFalse(DiagnosticPreferences(settings) { now }.logcatEnabled.value)
    }
}
