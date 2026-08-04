package com.yfuse.core.data

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchTogetherPreferencesTest {
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
