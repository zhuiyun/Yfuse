package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchTogetherPreferencesTest {
    @Test
    fun default_endpoint_uses_the_official_https_relay() {
        val preferences = WatchTogetherPreferences(MapSettings())
        assertEquals("https://47.112.219.60", preferences.endpoint.value)
    }

    @Test
    fun legacy_official_endpoint_is_migrated_to_the_account_service() {
        val settings = MapSettings().apply { putString("watchTogether.endpoint", "http://47.112.219.60") }
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, WatchTogetherPreferences(settings).endpoint.value)
    }

    @Test
    fun blocked_domain_endpoint_is_migrated_to_ip_https() {
        val settings = MapSettings().apply { putString("watchTogether.endpoint", "https://yfuse.zhuiyun.site") }
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, WatchTogetherPreferences(settings).endpoint.value)
    }

    @Test
    fun persisted_custom_endpoints_are_always_migrated_even_after_old_migration_markers() {
        val settings =
            MapSettings().apply {
                putString("watchTogether.endpoint", "wss://watch.example.com/relay")
                putBoolean("watchTogether.endpointHttpsMigration.v2", true)
                putBoolean("watchTogether.endpointCleartextConfirmed.v1", true)
            }

        val preferences = WatchTogetherPreferences(settings)
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, preferences.endpoint.value)
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, settings.getString("watchTogether.endpoint", ""))
        assertFalse(settings.getBoolean("watchTogether.endpointCleartextConfirmed.v1", true))
    }

    @Test
    fun official_endpoint_policy_rejects_aliases_paths_and_other_transports() {
        assertTrue(WatchTogetherPreferences.isOfficialEndpoint(" https://47.112.219.60/ "))
        assertFalse(WatchTogetherPreferences.isOfficialEndpoint("wss://47.112.219.60"))
        assertFalse(WatchTogetherPreferences.isOfficialEndpoint("https://47.112.219.60/watch"))
        assertFalse(WatchTogetherPreferences.isOfficialEndpoint("https://47.112.219.60:443"))
        assertFalse(WatchTogetherPreferences.isOfficialEndpoint("https://watch.example.com"))
    }

    @Test
    fun endpoint_state_cannot_be_changed_after_a_legacy_value_is_migrated() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)
        settings.putString("watchTogether.endpoint", "https://watch.example.com")
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, preferences.endpoint.value)
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, WatchTogetherPreferences(settings).endpoint.value)
    }

    @Test
    fun profile_is_normalized_and_persisted() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)
        preferences.setProfile("  小影迷 👨‍👩‍👧‍👦\n", 5)
        assertEquals("小影迷 👨‍👩‍👧‍👦", preferences.nickname.value)
        assertEquals(5, preferences.avatarId.value)
        val restored = WatchTogetherPreferences(settings)
        assertEquals("小影迷 👨‍👩‍👧‍👦", restored.nickname.value)
        assertEquals(5, restored.avatarId.value)
    }
}
