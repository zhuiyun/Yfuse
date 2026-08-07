package com.yfuse.core.logging

import com.russhwolf.settings.MapSettings
import com.yfuse.core.data.DiagnosticPreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLogcatOutputGateTest {
    @Test
    fun runtime_gate_tracks_the_persisted_diagnostic_switch() {
        val preferences = DiagnosticPreferences(MapSettings())
        SafeLogcatOutputGate.initialize(preferences)

        assertFalse(SafeLogcatOutputGate.isEnabled())
        preferences.setLogcatEnabled(true)
        assertTrue(SafeLogcatOutputGate.isEnabled())
        preferences.setLogcatEnabled(false)
        assertFalse(SafeLogcatOutputGate.isEnabled())
    }
}
