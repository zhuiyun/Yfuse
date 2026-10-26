package com.yfuse.core.widget

import com.russhwolf.settings.MapSettings
import com.yfuse.core.security.SecureStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetCutoutPreferencesTest {
    private class Secrets : SecureStore {
        private val values = mutableMapOf<String, ByteArray>()

        override fun get(key: String) = values[key]?.copyOf()

        override fun put(
            key: String,
            value: ByteArray,
        ) {
            values[key] = value.copyOf()
        }

        override fun remove(key: String) = values.remove(key) != null

        override fun clear() = values.clear()
    }

    @Test
    fun key_is_kept_out_of_plain_settings_and_empty_save_preserves_it() {
        val settings = MapSettings()
        val secrets = Secrets()
        val preferences = WidgetCutoutPreferences(settings, secrets)
        assertFalse(preferences.configuration.value.configured)
        preferences.save(true, "test-poof-key")
        assertEquals(setOf("widget.poof.enabled"), settings.keys)
        assertTrue(preferences.configuration.value.configured)
        preferences.save(false, "")
        val restarted = WidgetCutoutPreferences(settings, secrets)
        assertEquals("test-poof-key", restarted.apiKey())
        assertFalse(restarted.configuration.value.enabled)
        restarted.removeKey()
        assertEquals("", restarted.apiKey())
        assertFalse(restarted.configuration.value.configured)
    }

    @Test
    fun invalid_key_cannot_replace_a_working_credential() {
        val preferences = WidgetCutoutPreferences(MapSettings(), Secrets())
        preferences.save(true, "good-key")
        assertFailsWith<IllegalArgumentException> { preferences.save(true, "header\nvalue") }
        assertEquals("good-key", preferences.apiKey())
    }
}
