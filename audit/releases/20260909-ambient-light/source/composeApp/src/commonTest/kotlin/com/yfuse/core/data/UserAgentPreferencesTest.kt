package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class UserAgentPreferencesTest {
    @Test
    fun custom_user_agent_is_sanitized_and_persisted() {
        val settings = MapSettings()
        UserAgentPreferences(settings).setUserAgent("  Yfuse/Test\r\nInjected  ")

        assertEquals("Yfuse/TestInjected", UserAgentPreferences(settings).userAgent.value)
    }
}
