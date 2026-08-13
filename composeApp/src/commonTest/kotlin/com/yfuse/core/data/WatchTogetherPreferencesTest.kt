package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EndpointTransportDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchTogetherPreferencesTest {
    @Test
    fun default_endpoint_uses_the_official_https_relay() {
        val preferences = WatchTogetherPreferences(MapSettings())
        assertEquals("https://47.112.219.60", preferences.endpoint.value)
    }

    @Test
    fun public_http_endpoint_can_be_saved_without_confirmation() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)

        val saved = preferences.setEndpoint("http://47.112.219.60:8080/")

        assertTrue(saved.allowed)
        assertEquals(EndpointTransportDecision.Cleartext, saved.decision)
        assertEquals("http://47.112.219.60:8080", preferences.endpoint.value)
        assertEquals("http://47.112.219.60:8080", WatchTogetherPreferences(settings).endpoint.value)
    }

    @Test
    fun persisted_cleartext_endpoint_is_kept() {
        val settings =
            MapSettings().apply {
                putString("watchTogether.endpoint", "http://47.112.219.60:8080")
            }
        assertEquals("http://47.112.219.60:8080", WatchTogetherPreferences(settings).endpoint.value)
    }

    @Test
    fun ws_and_wss_custom_endpoints_are_normalized_and_persisted() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)
        assertTrue(preferences.setEndpoint("  ws://192.168.1.20:8080/socket/  ").allowed)
        assertEquals("ws://192.168.1.20:8080/socket", preferences.endpoint.value)
        assertTrue(preferences.setEndpoint("  wss://watch.example.com/socket/  ").allowed)
        assertEquals("wss://watch.example.com/socket", preferences.endpoint.value)
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
