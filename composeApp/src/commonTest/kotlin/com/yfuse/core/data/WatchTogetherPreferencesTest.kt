package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import com.yfuse.core.network.EndpointTransportDecision
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
    fun legacy_official_endpoint_is_migrated_and_cannot_be_saved_as_public_cleartext() {
        val settings =
            MapSettings().apply {
                putString("watchTogether.endpoint", "http://47.112.219.60")
            }

        val migrated = WatchTogetherPreferences(settings)
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, migrated.endpoint.value)
        assertEquals(
            WatchTogetherPreferences.DEFAULT_ENDPOINT,
            settings.getString("watchTogether.endpoint", ""),
        )

        val rejected =
            migrated.setEndpoint(
                "http://47.112.219.60",
                localCleartextConfirmed = true,
            )
        assertFalse(rejected.allowed)
        assertEquals(EndpointTransportDecision.PublicCleartextRejected, rejected.decision)
        assertEquals(
            WatchTogetherPreferences.DEFAULT_ENDPOINT,
            WatchTogetherPreferences(settings).endpoint.value,
        )
    }

    @Test
    fun blocked_domain_endpoint_is_migrated_to_ip_https() {
        val settings =
            MapSettings().apply {
                putString("watchTogether.endpoint", "https://yfuse.zhuiyun.site")
            }

        assertEquals(
            WatchTogetherPreferences.DEFAULT_ENDPOINT,
            WatchTogetherPreferences(settings).endpoint.value,
        )
    }

    @Test
    fun persisted_cleartext_endpoint_fails_closed_without_current_explicit_consent() {
        val settings =
            MapSettings().apply {
                putString("watchTogether.endpoint", "http://47.112.219.60:8080")
            }

        assertEquals(
            WatchTogetherPreferences.DEFAULT_ENDPOINT,
            WatchTogetherPreferences(settings).endpoint.value,
        )
    }

    @Test
    fun local_cleartext_can_only_be_saved_after_explicit_confirmation() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)

        val pending = preferences.setEndpoint("ws://192.168.1.20:8080")
        assertFalse(pending.allowed)
        assertTrue(pending.requiresCleartextConfirmation)
        assertEquals(WatchTogetherPreferences.DEFAULT_ENDPOINT, preferences.endpoint.value)

        val saved =
            preferences.setEndpoint(
                "ws://192.168.1.20:8080/",
                localCleartextConfirmed = true,
            )
        assertTrue(saved.allowed)
        assertEquals("ws://192.168.1.20:8080", preferences.endpoint.value)
        assertEquals(
            "ws://192.168.1.20:8080",
            WatchTogetherPreferences(settings).endpoint.value,
        )
    }

    @Test
    fun secure_custom_endpoint_is_normalized_and_persisted() {
        val settings = MapSettings()
        val preferences = WatchTogetherPreferences(settings)

        val saved = preferences.setEndpoint("  wss://watch.example.com/socket/  ")

        assertTrue(saved.allowed)
        assertEquals("wss://watch.example.com/socket", preferences.endpoint.value)
        assertEquals(
            "wss://watch.example.com/socket",
            WatchTogetherPreferences(settings).endpoint.value,
        )
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
        assertEquals(true, restored.chatPreviewEnabled.value)
        assertEquals(true, restored.chatDanmakuEnabled.value)
        restored.setChatPreviewEnabled(false)
        restored.setChatDanmakuEnabled(false)
        assertEquals(false, WatchTogetherPreferences(settings).chatPreviewEnabled.value)
        assertEquals(false, WatchTogetherPreferences(settings).chatDanmakuEnabled.value)
    }

    @Test
    fun invalid_profile_values_fall_back_to_safe_defaults() {
        val preferences = WatchTogetherPreferences(MapSettings())
        preferences.setProfile(" \n ", 99)

        assertEquals(WatchTogetherPreferences.DEFAULT_NICKNAME, preferences.nickname.value)
        assertEquals(WatchTogetherPreferences.AVATAR_COUNT - 1, preferences.avatarId.value)

        preferences.setProfile("a" + "\u0301".repeat(2_000), 0)
        assertEquals(WatchTogetherPreferences.DEFAULT_NICKNAME, preferences.nickname.value)
    }
}
